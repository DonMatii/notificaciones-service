package cl.ochodigital.pasteleriamydreams.notificacionesservice.event;

import java.time.LocalDateTime;

// Espejo del record SolicitudCreadoEvent que pedidos-service publica en el topic
// `solicitudes` cuando nace una solicitud de pedido de la Versión 1 (S2).
// Misma forma que PedidoCreadoEvent: evento, id y los datos del cliente.
public record SolicitudCreadaEvent(
        String evento,
        Long id,
        String nombre,
        String email,
        String telefono,
        String detalle,
        String mensaje,
        LocalDateTime fecha) {
}
