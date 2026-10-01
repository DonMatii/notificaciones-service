package cl.ochodigital.pasteleriamydreams.notificacionesservice.event;

import java.time.LocalDateTime;

// Mirrors the JSON contract published by pedidos-service on topic `pedidos`
// (producer record PedidoCreadoEvent, serialized with the Spring ObjectMapper).
// Field names must match exactly: evento, id, cliente, email, producto, cantidad, total, fecha.
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
