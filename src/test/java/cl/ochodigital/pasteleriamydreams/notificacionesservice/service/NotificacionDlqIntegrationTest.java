package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Política de errores del consumidor (rúbrica: éxito / error recuperable /
// error definitivo): un mensaje roto de forma definitiva va a la DLQ sin
// reintentos y sin bloquear el topic; una falla transitoria (p. ej. la base
// no responde) se reintenta y se recupera sin ir a la DLQ.
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"pedidos", "pedidos.DLT"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "app.kafka.topic=pedidos",
        "app.kafka.reintentos.max=2",
        "app.kafka.reintentos.espera-ms=50"
})
class NotificacionDlqIntegrationTest {

    private static final String TOPICO = "pedidos";
    private static final String TOPICO_DLT = "pedidos.DLT";
    private static final String EVENTO_CORRUPTO = "this is not json {{{";
    private static final String EVENTO_BUENO = """
            {"evento":"PedidoCreado","id":301,"cliente":"Ana Rojas","email":"ana@ejemplo.cl","producto":"Torta Moka","cantidad":1,"total":10000,"fecha":"2026-11-10T10:00:00"}""";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker kafkaEmbebido;

    @SpyBean
    private NotificacionRepository notificacionRepository;

    @Test
    void mensajeDefinitivamenteRotoVaALaDltYNoBloqueaElTopic() throws Exception {
        long filasAntes = notificacionRepository.count();

        // Mensaje ilegible: falla DEFINITIVA -> la DLQ lo recibe sin reintentos
        kafkaTemplate.send(TOPICO, EVENTO_CORRUPTO).get();

        try (Consumer<String, String> lectorDlt = crearLectorDlt()) {
            ConsumerRecord<String, String> enDlt = esperarRegistro(lectorDlt, 20_000);
            assertNotNull(enDlt, "el mensaje corrupto debía quedar aislado en la DLQ");
            assertEquals(EVENTO_CORRUPTO, enDlt.value());
        }

        // Y el consumidor sigue vivo: el siguiente mensaje bueno se procesa
        kafkaTemplate.send(TOPICO, EVENTO_BUENO).get();
        esperarHasta(() -> notificacionRepository.count() == filasAntes + 1, 20_000);
    }

    @Test
    void falloTransitorioSeReintentaYSeRecuperaSinIrALaDlt() throws Exception {
        // Falla TRANSITORIA: el primer guardado falla como si la base no
        // respondiera; el reintento entra y el segundo intento pasa. (El
        // repositorio es un proxy JPA: el éxito del reintento se simula con un
        // retorno nulo y lo que se verifica es el reintento y la DLQ vacía)
        doThrow(new DataAccessResourceFailureException("base no disponible"))
                .doReturn(null)
                .when(notificacionRepository).save(ArgumentMatchers.any());

        kafkaTemplate.send(TOPICO, EVENTO_BUENO).get();

        // El primer intento falló y hubo al menos un reintento que siguió
        verify(notificacionRepository, timeout(20_000).atLeast(2)).save(ArgumentMatchers.any());

        // Nada terminó en la DLQ: la falla era recuperable
        try (Consumer<String, String> lectorDlt = crearLectorDlt()) {
            assertNull(esperarRegistro(lectorDlt, 3_000), "una falla transitoria no debía ir a la DLQ");
        }
    }

    private Consumer<String, String> crearLectorDlt() {
        Map<String, Object> props = new HashMap<>(
                KafkaTestUtils.consumerProps("verificacion-dlt", "false", kafkaEmbebido));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> lector = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props);
        lector.subscribe(List.of(TOPICO_DLT));
        return lector;
    }

    private ConsumerRecord<String, String> esperarRegistro(Consumer<String, String> lector, long msMax) {
        long fin = System.currentTimeMillis() + msMax;
        while (System.currentTimeMillis() < fin) {
            ConsumerRecords<String, String> registros = lector.poll(Duration.ofMillis(500));
            if (!registros.isEmpty()) {
                return registros.iterator().next();
            }
        }
        return null;
    }

    private void esperarHasta(java.util.function.BooleanSupplier condicion, long msMax)
            throws InterruptedException {
        long fin = System.currentTimeMillis() + msMax;
        while (System.currentTimeMillis() < fin) {
            if (condicion.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        fail("la condición no se cumplió en " + msMax + " ms");
    }
}
