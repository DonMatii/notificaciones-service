package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

// Verifies the consumer end to end: a real producer payload creates exactly one
// notification row, redelivery of the same event is idempotent, and a broken
// (poison) message never kills the consumer.
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"pedidos"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "app.kafka.topic=pedidos"
})
class NotificacionConsumoIntegrationTest {

    private static final String TOPICO = "pedidos";

    // Exact JSON contract published by pedidos-service (record PedidoCreadoEvent
    // serialized with the Spring ObjectMapper: ISO-8601 LocalDateTime)
    private static final String EVENTO_PEDIDO_101 = """
            {"evento":"PedidoCreado","id":101,"cliente":"Daniela Soto","email":"daniela@ejemplo.cl","producto":"Torta de chocolate, Cupcakes vainilla","cantidad":7,"total":33990,"fecha":"2026-10-01T12:30:00"}""";

    private static final String EVENTO_PEDIDO_102 = """
            {"evento":"PedidoCreado","id":102,"cliente":"Raul Pino","email":"raul@ejemplo.cl","producto":"Kuchen de manzana","cantidad":2,"total":9980,"fecha":"2026-10-01T12:35:00"}""";

    private static final String EVENTO_PEDIDO_201 = """
            {"evento":"PedidoCreado","id":201,"cliente":"Maria Soto","email":"maria@ejemplo.cl","producto":"Tres leches","cantidad":1,"total":12500,"fecha":"2026-10-01T13:00:00"}""";

    // Broken payloads: neither is a valid PedidoCreado event
    private static final String EVENTO_CORRUPTO = "this is not json {{{";
    private static final String EVENTO_SIN_ID = "{\"evento\":\"PedidoCreado\",\"cliente\":\"Sin id\"}";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private NotificacionRepository notificacionRepository;

    @Test
    void persistOneNotificationPerOrderEvenWhenTheEventIsRedelivered() throws Exception {
        long baseline = notificacionRepository.count();

        // 1) Publish a real producer payload: a notification row must appear
        publicar(EVENTO_PEDIDO_101);
        assertTrue(esperar(() -> notificacionRepository.existsByPedidoId(101L), 10_000),
                "The notification for order 101 must be persisted");
        assertEquals(baseline + 1, notificacionRepository.count());

        // The row carries the expected content and the initial PENDIENTE state
        // (these tests run without SMTP configured, so no email is attempted)
        Notificacion guardada = notificacionRepository.findByPedidoId(101L).orElseThrow();
        assertEquals("Pedido recibido - 101", guardada.getAsunto());
        assertEquals("Daniela Soto", guardada.getCliente());
        assertEquals("daniela@ejemplo.cl", guardada.getEmail());
        assertEquals("PENDIENTE", guardada.getEstado());
        assertNotNull(guardada.getFecha());
        assertTrue(guardada.getCuerpo().contains("Torta de chocolate"),
                "Body must include the products from the event");
        assertTrue(guardada.getCuerpo().contains("33990"),
                "Body must include the total from the event");

        // 2) Publish the SAME event again, followed by a sentinel order.
        //    The topic has one partition and the consumer is single-threaded, so the
        //    sentinel is processed strictly AFTER the duplicate: once the sentinel row
        //    exists, the duplicate has already been handled and skipped.
        publicar(EVENTO_PEDIDO_101);
        publicar(EVENTO_PEDIDO_102);
        assertTrue(esperar(() -> notificacionRepository.existsByPedidoId(102L), 10_000),
                "The sentinel order 102 must be persisted (proves the duplicate was consumed)");

        // Exactly ONE row for order 101, and exactly two new rows in total
        assertNotNull(notificacionRepository.findByPedidoId(101L).orElseThrow(),
                "Order 101 must still have a notification");
        assertEquals(baseline + 2, notificacionRepository.count(),
                "The duplicated event must not create a second row");
    }

    @Test
    void brokenPayloadsAreLoggedAndTheConsumerKeepsWorking() throws Exception {
        long baseline = notificacionRepository.count();

        // 1) Poison pills: unparsable JSON and a parsable event without id.
        //    Neither must create a row, and neither may throw out of the listener.
        publicar(EVENTO_CORRUPTO);
        publicar(EVENTO_SIN_ID);

        // 2) A valid event right after the poison pills: the consumer must still work
        publicar(EVENTO_PEDIDO_201);
        assertTrue(esperar(() -> notificacionRepository.existsByPedidoId(201L), 10_000),
                "The consumer must survive broken payloads and process the next valid event");

        assertEquals(baseline + 1, notificacionRepository.count(),
                "Broken payloads must not create notification rows");
    }

    // Sends to the embedded broker and waits for the broker acknowledgment
    private void publicar(String payload) throws Exception {
        kafkaTemplate.send(TOPICO, payload).get(10, TimeUnit.SECONDS);
    }

    // Polls a condition until it is true or the timeout expires
    private boolean esperar(java.util.function.BooleanSupplier condicion, long timeoutMs)
            throws InterruptedException {
        long limite = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < limite) {
            if (condicion.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return condicion.getAsBoolean();
    }
}
