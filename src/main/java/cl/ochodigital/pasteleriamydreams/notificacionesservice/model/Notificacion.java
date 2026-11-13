package cl.ochodigital.pasteleriamydreams.notificacionesservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

// Notificación generada a partir de un evento PedidoCreado (RF-09)
@Entity
@Table(name = "notificaciones")
@Data
public class Notificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Clave de idempotencia: con entrega at-least-once el mismo pedido puede
    // llegar más de una vez, así que pedidoId es único y los duplicados se saltan
    @Column(nullable = false, unique = true)
    private Long pedidoId;

    private String cliente;

    private String email;

    private String asunto;

    // Texto del correo enviado por SMTP cuando MAIL_HOST está configurado (RF-09)
    @Column(length = 2000)
    private String cuerpo;

    // Código de seguimiento opaco (RF-11) que trae el evento PedidoCreado. Se
    // guarda para poder reconstruir el correo con la marca desde la fila
    // (nullable: los eventos más antiguos pueden no traerlo).
    // spring.jpa.hibernate.ddl-auto=update crea la columna.
    @Column(length = 64)
    private String codigoSeguimiento;

    // PENDIENTE hasta que corre el paso del correo; ENVIADO / ERROR una vez que se intenta SMTP
    private String estado = "PENDIENTE";

    // Cuándo se creó esta fila de notificación
    private LocalDateTime fecha = LocalDateTime.now();
}
