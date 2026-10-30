package cl.ochodigital.pasteleriamydreams.notificacionesservice.event;

import java.time.LocalDateTime;

// Mirrors the JSON contract published by pedidos-service on the topic
// `pedidos-estados` (producer record PedidoEstadoCambiadoEvent). Field names must
// match exactly: evento, id, cliente, email, estadoAnterior, estadoNuevo, total,
// fecha, codigoConsulta.
//
// It lives on its own topic on purpose: Notificacion.pedidoId is unique and the
// PedidoCreado consumer skips any second event for the same order, so a status
// change travelling on `pedidos` would be silently discarded.
public record PedidoEstadoCambiadoEvent(
        String evento,
        Long id,
        String cliente,
        String email,
        String estadoAnterior,
        String estadoNuevo,
        Integer total,
        LocalDateTime fecha,
        String codigoConsulta) {
}
