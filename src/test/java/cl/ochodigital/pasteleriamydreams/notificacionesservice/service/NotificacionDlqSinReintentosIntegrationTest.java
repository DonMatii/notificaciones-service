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

// VERIFIER PROBE (scratch only): a definitive failure must reach the listener ONCE.
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"pedidos", "pedidos.DLT"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "app.kafka.topic=pedidos",
        "app.kafka.reintentos.max=2",
        "app.kafka.reintentos.espera-ms=1000"
})
class NotificacionDlqSinReintentosIntegrationTest {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker kafkaEmbebido;
    @SpyBean private NotificacionListener listener;
    @SpyBean private NotificacionRepository notificacionRepository;

    @Test
    void elDefinitivoSeIntentaUnaSolaVez() throws Exception {
        String payload = "{\"evento\":\"PedidoCreado\",\"cliente\":\"Sin Id 2\",\"total\":2000}";
        long t0 = System.currentTimeMillis();
        kafkaTemplate.send("pedidos", payload).get();

        Map<String, Object> props = new HashMap<>(
                KafkaTestUtils.consumerProps("probe-" + UUID.randomUUID(), "false", kafkaEmbebido));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> lector = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props)) {
            lector.subscribe(List.of("pedidos.DLT"));
            boolean visto = false;
            long fin = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < fin && !visto) {
                ConsumerRecords<String, String> registros = lector.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, String> r : registros) {
                    if (payload.equals(r.value())) visto = true;
                }
            }
            assertTrue(visto, "el definitivo debia quedar en la DLT");
        }
        long ms = System.currentTimeMillis() - t0;
        verify(listener, times(1)).onPedidoCreado(ArgumentMatchers.eq(payload));
        verify(notificacionRepository, never()).save(ArgumentMatchers.any());
        assertTrue(ms < 5000, "DLT alcanzada en " + ms + " ms (incluye el join del consumidor de prueba)");
    }
}


