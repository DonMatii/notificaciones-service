package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.event.PedidoCreadoEvent;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

// Consumes PedidoCreado events from topic `pedidos` and persists a notification
// per order (RF-09). When SMTP is configured (MAIL_HOST set) the confirmation
// email is sent right after the save and the row moves to ENVIADO / ERROR;
// without SMTP the row stays PENDIENTE, exactly as before.
@Service
public class NotificacionListener {

    private static final Logger log = LoggerFactory.getLogger(NotificacionListener.class);

    // The consumer group this service belongs to
    private static final String GRUPO_CONSUMIDOR = "notificaciones";

    private final NotificacionRepository notificacionRepository;
    private final ObjectMapper objectMapper;
    private final EmailSenderService emailSenderService;

    public NotificacionListener(NotificacionRepository notificacionRepository,
                                ObjectMapper objectMapper,
                                EmailSenderService emailSenderService) {
        this.notificacionRepository = notificacionRepository;
        this.objectMapper = objectMapper;
        this.emailSenderService = emailSenderService;
    }

    // Entry point for every message on the topic. Contract:
    //  - never throws (poison-pill tolerance: a broken message is logged and skipped)
    //  - duplicate pedidoId is skipped silently (at-least-once redelivery is expected)
    @KafkaListener(topics = "${app.kafka.topic:pedidos}", groupId = GRUPO_CONSUMIDOR)
    public void onPedidoCreado(String payload) {
        try {
            PedidoCreadoEvent evento = objectMapper.readValue(payload, PedidoCreadoEvent.class);
            if (evento == null || evento.id() == null) {
                log.error("Skipping event without order id: {}", payload);
                return;
            }

            // Idempotency guard: the same pedidoId must produce exactly one row
            if (notificacionRepository.existsByPedidoId(evento.id())) {
                log.debug("Duplicate event for order {} skipped", evento.id());
                return;
            }

            Notificacion notificacion = construirNotificacion(evento);
            notificacionRepository.save(notificacion);
            log.info("Notification persisted for order {} with state PENDIENTE", evento.id());
            aplicarEnvioCorreo(notificacion);
        } catch (DataIntegrityViolationException e) {
            // Lost a race against a concurrent save of the same pedidoId
            log.debug("Duplicate event for order detected on save, skipped: {}", e.getMessage());
        } catch (Exception e) {
            // Poison pill: log honestly and continue so one bad message
            // never blocks the consumer or retries forever
            log.error("Could not process event, skipping. Payload: {} - Reason: {}",
                    payload, e.getMessage());
        }
    }

    // RF-09: hand the freshly saved row to the SMTP sender when one is configured.
    // Runs after the idempotency guard and the save. EmailSenderService swallows
    // its own errors and never throws, so the "listener never throws" contract
    // and the poison-pill handling above stay untouched.
    private void aplicarEnvioCorreo(Notificacion notificacion) {
        if (!emailSenderService.habilitado()) {
            log.info("SMTP not configured, notification stays PENDIENTE (order {})",
                    notificacion.getPedidoId());
            return;
        }
        boolean ok = emailSenderService.enviar(notificacion);
        notificacion.setEstado(ok ? "ENVIADO" : "ERROR");
        notificacionRepository.save(notificacion);
    }

    // Builds the notification row from the event data
    private Notificacion construirNotificacion(PedidoCreadoEvent evento) {
        Notificacion notificacion = new Notificacion();
        notificacion.setPedidoId(evento.id());
        notificacion.setCliente(valorPorDefecto(evento.cliente(), "cliente"));
        notificacion.setEmail(valorPorDefecto(evento.email(), "sin email"));
        notificacion.setAsunto("Pedido recibido - " + evento.id());
        notificacion.setCuerpo(armarCuerpo(evento));
        notificacion.setEstado("PENDIENTE");
        notificacion.setFecha(java.time.LocalDateTime.now());
        return notificacion;
    }

    // Plain-text body with cliente / producto / cantidad / total from the event
    private String armarCuerpo(PedidoCreadoEvent evento) {
        String cliente = valorPorDefecto(evento.cliente(), "cliente");
        String producto = valorPorDefecto(evento.producto(), "sin detalle");
        String cantidad = evento.cantidad() != null ? evento.cantidad().toString() : "sin dato";
        String total = evento.total() != null ? "$" + evento.total() : "sin dato";

        return String.format(
                "Hola %s,%n%n"
                        + "Tu pedido %d fue recibido correctamente.%n%n"
                        + "Productos: %s%n"
                        + "Cantidad total de articulos: %s%n"
                        + "Total: %s%n%n"
                        + "Estado: pendiente de confirmacion. Enviaremos la confirmacion "
                        + "por correo electronico cuando el pedido sea despachado.",
                cliente,
                evento.id() != null ? evento.id() : 0L,
                producto,
                cantidad,
                total);
    }

    // Null/blank-safe helper so a partial event never crashes the listener
    private String valorPorDefecto(String valor, String porDefecto) {
        return (valor == null || valor.isBlank()) ? porDefecto : valor;
    }
}
