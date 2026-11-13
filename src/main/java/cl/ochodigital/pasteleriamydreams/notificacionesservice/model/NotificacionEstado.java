package cl.ochodigital.pasteleriamydreams.notificacionesservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

// Un correo generado a partir de un evento PedidoEstadoCambiado (S3/S4).
//
// Deliberadamente una tabla SEPARADA de `notificaciones`: esa tiene pedidoId
// único porque un pedido produce exactamente un correo de confirmación, mientras
// que acá un mismo pedido recorre hasta tres estados y debe producir una fila
// por estado. spring.jpa.hibernate.ddl-auto=update crea la tabla; ninguna fila
// existente se mueve.
@Entity
@Table(name = "notificaciones_estado",
        uniqueConstraints = @UniqueConstraint(columnNames = {"pedidoId", "estadoPedido"}))
@Data
public class NotificacionEstado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Primera mitad de la clave de idempotencia: con entrega at-least-once el
    // mismo cambio de estado puede llegar más de una vez
    @Column(nullable = false)
    private Long pedidoId;

    // Segunda mitad de la clave de idempotencia: EN_PREPARACION / DESPACHADO / ENTREGADO.
    // El estado al que el cliente está PASANDO, que es lo que anuncia el correo.
    @Column(nullable = false, length = 32)
    private String estadoPedido;

    // El estado del que venía el pedido, guardado para que el correo pueda decir "paso de X a Y"
    @Column(length = 32)
    private String estadoAnterior;

    private String cliente;

    private String email;

    private String asunto;

    // Texto del correo enviado por SMTP cuando MAIL_HOST está configurado (S4)
    @Column(length = 2000)
    private String cuerpo;

    // Código de seguimiento opaco (RF-11) para el link público /estado del correo
    @Column(length = 64)
    private String codigoSeguimiento;

    // Productos del pedido serializados como JSON para el desglose en el correo
    @Column(columnDefinition = "TEXT")
    private String productosJson;

    // PENDIENTE hasta que corre el paso del correo; ENVIADO / ERROR una vez que se intenta SMTP.
    // Se llama estadoEnvio (y no estado) para no confundirlo con estadoPedido de arriba.
    private String estadoEnvio = "PENDIENTE";

    // Cuándo se creó esta fila de notificación
    private LocalDateTime fecha = LocalDateTime.now();
}
