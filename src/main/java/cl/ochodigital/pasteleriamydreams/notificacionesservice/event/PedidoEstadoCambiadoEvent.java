package cl.ochodigital.pasteleriamydreams.notificacionesservice.event;

import java.time.LocalDateTime;

// Replica el contrato JSON publicado por pedidos-service en el topic
// `pedidos-estados` (record del productor PedidoEstadoCambiadoEvent). Los
// nombres de los campos deben coincidir exactamente: evento, id, cliente, email,
// estadoAnterior, estadoNuevo, total, fecha, codigoConsulta, productos.
//
// Vive en su propio topic a propósito: Notificacion.pedidoId es único y el
// consumidor de PedidoCreado salta cualquier segundo evento del mismo pedido,
// así que un cambio de estado viajando por `pedidos` se descartaría en silencio.
public record PedidoEstadoCambiadoEvent(
        String evento,
        Long id,
        String cliente,
        String email,
        String estadoAnterior,
        String estadoNuevo,
        Integer total,
        LocalDateTime fecha,
        String codigoConsulta,
        java.util.List<ProductoEstado> productos) {

    public record ProductoEstado(
            String nombre,
            Integer cantidad,
            Integer precioUnitario,
            Integer subtotal) {
    }
}
