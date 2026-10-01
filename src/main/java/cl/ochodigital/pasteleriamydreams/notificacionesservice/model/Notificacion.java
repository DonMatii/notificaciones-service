package cl.ochodigital.pasteleriamydreams.notificacionesservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

// Notification generated from a PedidoCreado event (RF-09)
@Entity
@Table(name = "notificaciones")
@Data
public class Notificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Idempotency key: with at-least-once delivery the same order can arrive
    // more than once, so pedidoId is unique and duplicates are skipped
    @Column(nullable = false, unique = true)
    private Long pedidoId;

    private String cliente;

    private String email;

    private String asunto;

    // Email body text (email sending is NOT implemented yet: SMTP infra pending)
    @Column(length = 2000)
    private String cuerpo;

    // PENDIENTE until the email step exists (RF-09 is pending SMTP)
    private String estado = "PENDIENTE";

    // When this notification row was created
    private LocalDateTime fecha = LocalDateTime.now();
}
