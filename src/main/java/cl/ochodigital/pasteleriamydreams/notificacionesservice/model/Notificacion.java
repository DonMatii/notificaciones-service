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

    // Email body text sent over SMTP when MAIL_HOST is configured (RF-09)
    @Column(length = 2000)
    private String cuerpo;

    // Opaque tracking code (RF-11) carried by the PedidoCreado event. Stored so
    // the branded email can be rebuilt from the row (nullable: older events may
    // not carry it). spring.jpa.hibernate.ddl-auto=update creates the column.
    @Column(length = 64)
    private String codigoSeguimiento;

    // PENDIENTE until the email step runs; ENVIADO / ERROR once SMTP is attempted
    private String estado = "PENDIENTE";

    // When this notification row was created
    private LocalDateTime fecha = LocalDateTime.now();
}
