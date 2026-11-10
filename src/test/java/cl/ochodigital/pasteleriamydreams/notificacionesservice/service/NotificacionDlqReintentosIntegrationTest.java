package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// VERIFIER PROBE (scratch only): retry/DLQ contract with a 1000 ms backoff so the
// absence of retries is observable in time, not just in call counts.
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"pedidos", "pedidos.DLT"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "app.kafka.topic=pedidos",
        "app.kafka.reintentos.max=2",
        "app.kafka.reintentos.espera-ms=1000"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificacionDlqReintentosIntegrationTest {

    private static final String TOPICO = "pedidos";
    private static final String TOPICO_DLT = "pedidos.DLT";

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker kafkaEmbebido;
    @SpyBean private NotificacionRepository notificacionRepository;

    @Test @Order(1)
    void definitivoVaALaDltSinReintentosYConHeaders() throws Exception {
        String payload = "{\"evento\":\"PedidoCreado\",\"cliente\":\"Sin Id\",\"total\":1000}";
        long t0 = System.currentTimeMillis();
        kafkaTemplate.send(TOPICO, payload).get();

        try (Consumer<String, String> lector = crearLectorDlt()) {
            ConsumerRecord<String, String> rec = esperarPayload(lector, payload, 15_000);
            assertNotNull(rec, "evento sin id debia quedar en la DLT");
            long ms = System.currentTimeMillis() - t0;
            assertEquals(payload, rec.value(), "el payload original debe preservarse");
            assertNotNull(rec.headers().lastHeader("kafka_dlt-exception-fqcn"), "falta header de excepcion");
            assertNotNull(rec.headers().lastHeader("kafka_dlt-exception-message"), "falta header de mensaje");
            assertNotNull(rec.headers().lastHeader("kafka_dlt-original-topic"), "falta header de topic original");
            String causa = rec.headers().lastHeader("kafka_dlt-exception-cause-fqcn") != null
                    ? new String(rec.headers().lastHeader("kafka_dlt-exception-cause-fqcn").value()) : "";
            assertTrue(causa.contains("IllegalArgumentException"),
                    "la causa de la falla definitiva debe ser IllegalArgumentException, fue: " + causa);
            assertTrue(ms < 1500, "sin reintentos: la DLT llego a los " + ms + " ms (esperado < 1500)");
        }
        verify(notificacionRepository, never()).save(ArgumentMatchers.any());
    }

    @Test @Order(2)
    void transitorioPersistenteSeReintentaDosVecesYTerminaEnLaDlt() throws Exception {
        String payload = "{\"evento\":\"PedidoCreado\",\"id\":777,\"cliente\":\"DB Caida\",\"total\":5000}";
        doThrow(new DataAccessResourceFailureException("base no disponible"))
                .when(notificacionRepository).save(ArgumentMatchers.any());

        long t0 = System.currentTimeMillis();
        kafkaTemplate.send(TOPICO, payload).get();

        verify(notificacionRepository, timeout(25_000).times(3)).save(ArgumentMatchers.any());

        try (Consumer<String, String> lector = crearLectorDlt()) {
            ConsumerRecord<String, String> rec = esperarPayload(lector, payload, 10_000);
            assertNotNull(rec, "tras agotar los reintentos el mensaje debia ir a la DLT");
            long ms = System.currentTimeMillis() - t0;
            assertTrue(ms >= 1900, "debieron pasar 2 esperas de 1000 ms antes de la DLT; paso " + ms + " ms");
        }
    }

    @Test @Order(3)
    void duplicadoSeOmiteSinIrAlaDlt() throws Exception {
        String payload = "{\"evento\":\"PedidoCreado\",\"id\":888,\"cliente\":\"Dup\",\"email\":\"d@e.cl\",\"total\":7000}";
        long antes = notificacionRepository.count();
        kafkaTemplate.send(TOPICO, payload).get();
        esperarHasta(() -> notificacionRepository.count() == antes + 1, 20_000);

        kafkaTemplate.send(TOPICO, payload).get();
        Thread.sleep(2_000);
        assertEquals(antes + 1, notificacionRepository.count(), "el duplicado no debe crear otra fila");

        try (Consumer<String, String> lector = crearLectorDlt()) {
            assertNull(esperarPayload(lector, payload, 2_500), "un duplicado nunca va a la DLT");
        }
    }

    private Consumer<String, String> crearLectorDlt() {
        Map<String, Object> props = new HashMap<>(
                KafkaTestUtils.consumerProps("probe-" + UUID.randomUUID(), "false", kafkaEmbebido));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> lector = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props);
        lector.subscribe(List.of(TOPICO_DLT));
        return lector;
    }

    // Scans the DLT until the expected payload shows up (earlier probe records are ignored)
    private ConsumerRecord<String, String> esperarPayload(Consumer<String, String> lector, String payload, long msMax) {
        long fin = System.currentTimeMillis() + msMax;
        while (System.currentTimeMillis() < fin) {
            ConsumerRecords<String, String> registros = lector.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> rec : registros) {
                if (payload.equals(rec.value())) {
                    return rec;
                }
            }
        }
        return null;
    }

    private void esperarHasta(java.util.function.BooleanSupplier condicion, long msMax) throws InterruptedException {
        long fin = System.currentTimeMillis() + msMax;
        while (System.currentTimeMillis() < fin) {
            if (condicion.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("condicion no cumplida en " + msMax + " ms");
    }
}

