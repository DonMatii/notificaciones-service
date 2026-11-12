package cl.ochodigital.pasteleriamydreams.notificacionesservice.repository;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.NotificacionSolicitud;
import org.springframework.data.jpa.repository.JpaRepository;

// Acceso a las notificaciones de solicitudes de pedido (tabla `notificaciones_solicitud`)
public interface NotificacionSolicitudRepository extends JpaRepository<NotificacionSolicitud, Long> {

    // Control de duplicados: una solicitud produce exactamente un correo
    boolean existsBySolicitudId(Long solicitudId);
}
