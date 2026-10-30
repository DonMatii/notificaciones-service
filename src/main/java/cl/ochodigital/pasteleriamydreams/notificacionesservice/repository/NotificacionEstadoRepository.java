package cl.ochodigital.pasteleriamydreams.notificacionesservice.repository;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.NotificacionEstado;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificacionEstadoRepository extends JpaRepository<NotificacionEstado, Long> {

    // The idempotency guard of the status consumer. It takes BOTH halves of the
    // key: skipping on pedidoId alone would drop the second and third state of
    // the same order, which is exactly what must not happen (S3).
    boolean existsByPedidoIdAndEstadoPedido(Long pedidoId, String estadoPedido);

    Optional<NotificacionEstado> findByPedidoIdAndEstadoPedido(Long pedidoId, String estadoPedido);

    List<NotificacionEstado> findAllByOrderByIdDesc();
}
