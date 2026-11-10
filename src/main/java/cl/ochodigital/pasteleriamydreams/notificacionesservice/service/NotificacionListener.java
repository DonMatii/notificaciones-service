package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.event.PedidoCreadoEvent;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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

    // Branded subject stored on the row and sent for every confirmation email:
    // the brand only, never the internal sequential id (RF-11 keeps it private)
    static final String ASUNTO_CONFIRMACION = "Pastelería My Dreams — Recibimos tu pedido";

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

    // Entry point for every message on the topic. Error policy (rubric:
    // success / recoverable failure / definitive failure):
    //  - duplicates are skipped by the idempotency guard (never thrown)
    //  - definitive failures (unreadable payload, event without id) are
    //    rethrown and the error handler isolates them in <topic>.DLT with no
    //    retries: nothing is lost in silence
    //  - transient failures (DB down, infra) are rethrown too: the error
    //    handler retries with backoff and only then moves the message to the DLQ
    @KafkaListener(topics = "${app.kafka.topic:pedidos}", groupId = GRUPO_CONSUMIDOR)
    public void onPedidoCreado(String payload) {
        try {
            PedidoCreadoEvent evento = objectMapper.readValue(payload, PedidoCreadoEvent.class);
            if (evento == null || evento.id() == null) {
                log.error("Event without order id, isolating to the DLQ: {}", payload);
                throw new IllegalArgumentException("event without order id");
            }

            // Idempotency guard: the same pedidoId must produce exactly one row
            if (notificacionRepository.existsByPedidoId(evento.id())) {
                log.debug("Duplicate event for order {} skipped", evento.id());
                return;
            }

            Notificacion notificacion = construirNotificacion(evento,
                    extraerCodigoSeguimiento(payload));
            notificacionRepository.save(notificacion);
            log.info("Notification persisted for order {} with state PENDIENTE", evento.id());
            aplicarEnvioCorreo(notificacion);
        } catch (DataIntegrityViolationException e) {
            // Lost a race against a concurrent save of the same pedidoId
            log.debug("Duplicate event for order detected on save, skipped: {}", e.getMessage());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Definitive failure: unreadable or invalid event -> no retries, the
            // error handler sends it straight to the DLQ for manual review
            log.error("Definitively invalid message, isolating to the DLQ. Payload: {} - Reason: {}",
                    payload, e.getMessage());
            throw new IllegalArgumentException("invalid PedidoCreado event", e);
        } catch (Exception e) {
            // Transient failure (DB down, infra): rethrown so the error handler
            // retries it and, if it persists, isolates it in the DLQ. The old
            // "log and skip" silently lost the order's confirmation email.
            log.error("Recoverable failure processing event. Payload: {} - Reason: {}",
                    payload, e.getMessage());
            throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e);
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
        try {
            notificacionRepository.save(notificacion);
        } catch (Exception e) {
            log.error("Could not persist final state {} for order {}: {}",
                    notificacion.getEstado(), notificacion.getPedidoId(), e.getMessage());
        }
    }

    // Builds the notification row from the event data plus the opaque tracking
    // code (nullable: events published before RF-11 do not carry one)
    private Notificacion construirNotificacion(PedidoCreadoEvent evento, String codigoSeguimiento) {
        Notificacion notificacion = new Notificacion();
        notificacion.setPedidoId(evento.id());
        notificacion.setCliente(valorPorDefecto(evento.cliente(), "cliente"));
        notificacion.setEmail(valorPorDefecto(evento.email(), "sin email"));
        notificacion.setAsunto(ASUNTO_CONFIRMACION);
        notificacion.setCuerpo(armarCuerpo(evento));
        notificacion.setCodigoSeguimiento(codigoSeguimiento);
        notificacion.setEstado("PENDIENTE");
        notificacion.setFecha(java.time.LocalDateTime.now());
        return notificacion;
    }

    // codigoConsulta (RF-11) rides along in the payload but the mirror record
    // does not model it, so it is read defensively from the raw JSON. Never
    // throws: a missing or odd value simply degrades to null.
    private String extraerCodigoSeguimiento(String payload) {
        try {
            JsonNode nodo = objectMapper.readTree(payload);
            JsonNode codigo = nodo != null ? nodo.get("codigoConsulta") : null;
            if (codigo == null || codigo.isNull()) {
                return null;
            }
            String valor = codigo.asText();
            return (valor == null || valor.isBlank()) ? null : valor.trim();
        } catch (Exception e) {
            log.debug("Could not read codigoConsulta from payload: {}", e.getMessage());
            return null;
        }
    }

    // Plain-text body with cliente / producto / cantidad / total from the event
    private String armarCuerpo(PedidoCreadoEvent evento) {
        String cliente = valorPorDefecto(evento.cliente(), "cliente");
        String producto = valorPorDefecto(evento.producto(), "sin detalle");
        String cantidad = evento.cantidad() != null ? evento.cantidad().toString() : "sin dato";
        String total = FormatoMoneda.clp(evento.total());

        return String.format(
                "Hola %s,%n%n"
                        + "Tu pedido fue recibido correctamente.%n%n"
                        + "Productos: %s%n"
                        + "Cantidad total de articulos: %s%n"
                        + "Total: %s%n%n"
                        + "Estado: pendiente de confirmacion. Enviaremos la confirmacion "
                        + "por correo electronico cuando el pedido sea despachado.",
                cliente,
                producto,
                cantidad,
                total);
    }

    // Null/blank-safe helper so a partial event never crashes the listener
    private String valorPorDefecto(String valor, String porDefecto) {
        return (valor == null || valor.isBlank()) ? porDefecto : valor;
    }
}
