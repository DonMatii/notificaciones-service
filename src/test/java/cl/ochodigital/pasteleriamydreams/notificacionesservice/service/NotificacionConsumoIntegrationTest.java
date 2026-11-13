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

// Verifica el consumidor de punta a punta: un payload real del productor crea
// exactamente una fila de notificación, la reentrega del mismo evento es
// idempotente y un mensaje roto (píldora de veneno) nunca mata al consumidor.
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"pedidos"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "app.kafka.topic=pedidos"
})
class NotificacionConsumoIntegrationTest {

    private static final String TOPICO = "pedidos";

    // Contrato JSON exacto publicado por pedidos-service (record PedidoCreadoEvent
    // serializado con el ObjectMapper de Spring: LocalDateTime ISO-8601, más el
    // código de seguimiento opaco codigoConsulta agregado por RF-11)
    private static final String EVENTO_PEDIDO_101 = """
            {"evento":"PedidoCreado","id":101,"cliente":"Daniela Soto","email":"daniela@ejemplo.cl","producto":"Torta de chocolate, Cupcakes vainilla","cantidad":7,"total":33990,"fecha":"2026-10-01T12:30:00","codigoConsulta":"0a1b2c3d4e5f60718293a4b5c6d7e8f9"}""";

    private static final String EVENTO_PEDIDO_102 = """
            {"evento":"PedidoCreado","id":102,"cliente":"Raul Pino","email":"raul@ejemplo.cl","producto":"Kuchen de manzana","cantidad":2,"total":9980,"fecha":"2026-10-01T12:35:00"}""";

    private static final String EVENTO_PEDIDO_201 = """
            {"evento":"PedidoCreado","id":201,"cliente":"Maria Soto","email":"maria@ejemplo.cl","producto":"Tres leches","cantidad":1,"total":12500,"fecha":"2026-10-01T13:00:00"}""";

    // Payloads rotos: ninguno es un evento PedidoCreado válido
    private static final String EVENTO_CORRUPTO = "this is not json {{{";
    private static final String EVENTO_SIN_ID = "{\"evento\":\"PedidoCreado\",\"cliente\":\"Sin id\"}";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private NotificacionRepository notificacionRepository;

    @Test
    void persistOneNotificationPerOrderEvenWhenTheEventIsRedelivered() throws Exception {
        long baseline = notificacionRepository.count();

        // 1) Publicar un payload real del productor: debe aparecer una fila de notificación
        publicar(EVENTO_PEDIDO_101);
        assertTrue(esperar(() -> notificacionRepository.existsByPedidoId(101L), 10_000),
                "The notification for order 101 must be persisted");
        assertEquals(baseline + 1, notificacionRepository.count());

        // La fila trae el contenido esperado y el estado inicial PENDIENTE
        // (estos tests corren sin SMTP configurado, así que no se intenta ningún correo)
        Notificacion guardada = notificacionRepository.findByPedidoId(101L).orElseThrow();
        assertEquals("Pastelería My Dreams — Recibimos tu pedido", guardada.getAsunto());
        assertEquals("0a1b2c3d4e5f60718293a4b5c6d7e8f9", guardada.getCodigoSeguimiento(),
                "The tracking code published by pedidos-service must be persisted");
        assertEquals("Daniela Soto", guardada.getCliente());
        assertEquals("daniela@ejemplo.cl", guardada.getEmail());
        assertEquals("PENDIENTE", guardada.getEstado());
        assertNotNull(guardada.getFecha());
        assertTrue(guardada.getCuerpo().contains("Torta de chocolate"),
                "Body must include the products from the event");
        assertTrue(guardada.getCuerpo().contains("$33.990"),
                "Body must include the total from the event, with the Chilean thousands separator");
        assertTrue(guardada.getCuerpo().contains("Tu pedido fue recibido correctamente"),
                "Body must confirm the order in generic wording");
        assertTrue(!guardada.getCuerpo().contains("Tu pedido 101"),
                "The sequential id must not leak into client-facing copy");

        // 2) Publicar el MISMO evento otra vez, seguido de un pedido centinela.
        //    El topic tiene una partición y el consumidor es monohilo, así que el
        //    centinela se procesa estrictamente DESPUÉS del duplicado: una vez que
        //    existe la fila del centinela, el duplicado ya fue procesado y salteado.
        publicar(EVENTO_PEDIDO_101);
        publicar(EVENTO_PEDIDO_102);
        assertTrue(esperar(() -> notificacionRepository.existsByPedidoId(102L), 10_000),
                "The sentinel order 102 must be persisted (proves the duplicate was consumed)");

        // Exactamente UNA fila para el pedido 101, y exactamente dos filas nuevas en total
        assertNotNull(notificacionRepository.findByPedidoId(101L).orElseThrow(),
                "Order 101 must still have a notification");
        assertEquals(baseline + 2, notificacionRepository.count(),
                "The duplicated event must not create a second row");
    }

    @Test
    void brokenPayloadsAreLoggedAndTheConsumerKeepsWorking() throws Exception {
        long baseline = notificacionRepository.count();

        // 1) Píldoras de veneno: un JSON ilegible y un evento parseable sin id.
        //    Ninguno debe crear fila, y ninguno puede salir del listener como excepción.
        publicar(EVENTO_CORRUPTO);
        publicar(EVENTO_SIN_ID);

        // 2) Un evento válido justo después de las píldoras de veneno: el consumidor debe seguir funcionando
        publicar(EVENTO_PEDIDO_201);
        assertTrue(esperar(() -> notificacionRepository.existsByPedidoId(201L), 10_000),
                "The consumer must survive broken payloads and process the next valid event");

        assertEquals(baseline + 1, notificacionRepository.count(),
                "Broken payloads must not create notification rows");
    }

    // Envía al broker embebido y espera el acknowledgment del broker
    private void publicar(String payload) throws Exception {
        kafkaTemplate.send(TOPICO, payload).get(10, TimeUnit.SECONDS);
    }

    // Consulta una condición hasta que se cumpla o expire el timeout
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
