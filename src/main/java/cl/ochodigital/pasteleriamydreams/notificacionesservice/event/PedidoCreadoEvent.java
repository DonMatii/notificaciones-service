package cl.ochodigital.pasteleriamydreams.notificacionesservice.event;

import java.time.LocalDateTime;

// Replica el contrato JSON publicado por pedidos-service en el topic `pedidos`
// (record del productor PedidoCreadoEvent, serializado con el ObjectMapper de Spring).
// Los nombres de los campos deben coincidir exactamente: evento, id, cliente, email, producto, cantidad, total, fecha.
public record PedidoCreadoEvent(
        String evento,
        Long id,
        String cliente,
        String email,
        String producto,
        Integer cantidad,
        Integer total,
        LocalDateTime fecha) {
}
