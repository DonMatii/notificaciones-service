package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

// VERIFIER PROBE (scratch only): the status topic (pedidos-estados) gets the same policy.
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"pedidos", "pedidos-estados", "pedidos-estados.DLT"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "app.kafka.topic=pedidos",
        "app.kafka.topic.estados=pedidos-estados",
        "app.kafka.reintentos.max=2",
        "app.kafka.reintentos.espera-ms=1000"
})
class NotificacionEstadoDlqIntegrationTest {

    private static final String TOPICO_ESTADOS = "pedidos-estados";
    private static final String TOPICO_DLT = "pedidos-estados.DLT";

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker kafkaEmbebido;

    @Test
    void venenoEnElTopicDeEstadosVaASuPropiaDltSinReintentos() throws Exception {
        String payload = "no soy json en el topic de estados @@@";
        long t0 = System.currentTimeMillis();
        kafkaTemplate.send(TOPICO_ESTADOS, payload).get();

        Map<String, Object> props = new HashMap<>(
                KafkaTestUtils.consumerProps("probe-" + UUID.randomUUID(), "false", kafkaEmbebido));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> lector = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props)) {
            lector.subscribe(List.of(TOPICO_DLT));
            ConsumerRecord<String, String> rec = null;
            long fin = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < fin && rec == null) {
                ConsumerRecords<String, String> registros = lector.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, String> r : registros) {
                    if (payload.equals(r.value())) rec = r;
                }
            }
            assertNotNull(rec, "el veneno del topic de estados debia quedar en pedidos-estados.DLT");
            long ms = System.currentTimeMillis() - t0;
            assertTrue(ms < 1500, "sin reintentos: llego a los " + ms + " ms");
            assertNotNull(rec.headers().lastHeader("kafka_dlt-original-topic"));
        }
    }
}

