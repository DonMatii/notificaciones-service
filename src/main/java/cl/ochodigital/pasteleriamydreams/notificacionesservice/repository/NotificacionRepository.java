package cl.ochodigital.pasteleriamydreams.notificacionesservice.repository;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {

    // Usado por el listener para saltear entregas duplicadas del mismo pedido
    boolean existsByPedidoId(Long pedidoId);

    Optional<Notificacion> findByPedidoId(Long pedidoId);

    List<Notificacion> findAllByOrderByIdDesc();
}
