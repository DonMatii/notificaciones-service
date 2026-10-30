package cl.ochodigital.pasteleriamydreams.notificacionesservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

// One email generated from a PedidoEstadoCambiado event (S3/S4).
//
// Deliberately a SEPARATE table from `notificaciones`: that one has pedidoId
// unique because an order produces exactly one confirmation email, while here a
// single order walks up to three states and must produce one row per state.
// spring.jpa.hibernate.ddl-auto=update creates the table; no existing row moves.
@Entity
@Table(name = "notificaciones_estado",
        uniqueConstraints = @UniqueConstraint(columnNames = {"pedidoId", "estadoPedido"}))
@Data
public class NotificacionEstado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // First half of the idempotency key: with at-least-once delivery the same
    // state change can arrive more than once
    @Column(nullable = false)
    private Long pedidoId;

    // Second half of the idempotency key: EN_PREPARACION / DESPACHADO / ENTREGADO.
    // The state the customer is moving TO, which is what the email announces.
    @Column(nullable = false, length = 32)
    private String estadoPedido;

    // The state the order came FROM, kept so the email can say "paso de X a Y"
    @Column(length = 32)
    private String estadoAnterior;

    private String cliente;

    private String email;

    private String asunto;

    // Email body text sent over SMTP when MAIL_HOST is configured (S4)
    @Column(length = 2000)
    private String cuerpo;

    // Opaque tracking code (RF-11) for the public /estado link in the email
    @Column(length = 64)
    private String codigoSeguimiento;

    // Productos del pedido serializados como JSON para el desglose en el correo
    @Column(columnDefinition = "TEXT")
    private String productosJson;

    // PENDIENTE until the email step runs; ENVIADO / ERROR once SMTP is attempted.
    // Named estadoEnvio (not estado) to keep it apart from estadoPedido above.
    private String estadoEnvio = "PENDIENTE";

    // When this notification row was created
    private LocalDateTime fecha = LocalDateTime.now();
}
