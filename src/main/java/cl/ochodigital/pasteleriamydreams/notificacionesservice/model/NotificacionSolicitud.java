package cl.ochodigital.pasteleriamydreams.notificacionesservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

// Un correo generado a partir de un evento SolicitudCreado (Versión 1, S2).
//
// Es DELIBERADAMENTE una tabla separada de `notificaciones`: esa tiene pedidoId
// único porque un pedido produce exactamente un correo de confirmación, y
// pedidos y solicitudes tienen secuencias de id independientes en la misma base
// compartida, así que un id de solicitud chocaría con un id de pedido y su
// correo se saltaría como duplicado. Misma razón que NotificacionEstado.
// spring.jpa.hibernate.ddl-auto=update crea la tabla; ninguna fila existente se mueve.
@Entity
@Table(name = "notificaciones_solicitud")
@Data
public class NotificacionSolicitud {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Clave de idempotencia: con entrega at-least-once el mismo evento de
    // solicitud puede llegar más de una vez, así que solicitudId es único y
    // los duplicados se saltan
    @Column(nullable = false, unique = true)
    private Long solicitudId;

    private String nombre;

    private String email;

    private String asunto;

    // Cuerpo del correo enviado por SMTP cuando MAIL_HOST está configurado (S2)
    @Column(columnDefinition = "TEXT")
    private String cuerpo;

    // PENDIENTE hasta que corre el paso del correo; ENVIADO / ERROR una vez
    // intentado el envío por SMTP
    private String estado = "PENDIENTE";

    // Cuándo se creó esta fila de notificación
    private LocalDateTime fecha = LocalDateTime.now();
}
