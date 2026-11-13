package cl.ochodigital.pasteleriamydreams.notificacionesservice.repository;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.NotificacionEstado;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificacionEstadoRepository extends JpaRepository<NotificacionEstado, Long> {

    // El control de idempotencia del consumidor de estados. Usa las DOS mitades
    // de la clave: saltar solo por pedidoId descartaría el segundo y el tercer
    // estado del mismo pedido, que es justamente lo que no debe pasar (S3).
    boolean existsByPedidoIdAndEstadoPedido(Long pedidoId, String estadoPedido);

    Optional<NotificacionEstado> findByPedidoIdAndEstadoPedido(Long pedidoId, String estadoPedido);

    List<NotificacionEstado> findAllByOrderByIdDesc();
}
