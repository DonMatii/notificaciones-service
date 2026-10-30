package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.event.PedidoEstadoCambiadoEvent;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.NotificacionEstado;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionEstadoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

// Consumes PedidoEstadoCambiado events from the topic `pedidos-estados` and
// persists ONE notification per (order, state) (S3). When SMTP is configured
// (MAIL_HOST set) the status email is sent right after the save and the row
// moves to ENVIADO / ERROR; without SMTP the row stays PENDIENTE.
//
// Same listener contract as NotificacionListener: never throws, tolerates
// poison pills, and skips duplicates. The one deliberate difference is the
// idempotency key: here it is the PAIR (pedidoId, estadoPedido), because a
// single order walks up to three states and must produce three emails.
@Service
public class NotificacionEstadoListener {

    private static final Logger log = LoggerFactory.getLogger(NotificacionEstadoListener.class);

    // Separate consumer group from the order-confirmation listener: the two
    // consume different topics, and keeping them apart makes offsets independent
    private static final String GRUPO_CONSUMIDOR = "notificaciones-estados";

    private final NotificacionEstadoRepository repository;
    private final ObjectMapper objectMapper;
    private final EmailSenderService emailSenderService;

    public NotificacionEstadoListener(NotificacionEstadoRepository repository,
                                      ObjectMapper objectMapper,
                                      EmailSenderService emailSenderService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.emailSenderService = emailSenderService;
    }

    // Entry point for every message on the status topic
    @KafkaListener(topics = "${app.kafka.topic.estados:pedidos-estados}", groupId = GRUPO_CONSUMIDOR)
    public void onPedidoEstadoCambiado(String payload) {
        try {
            PedidoEstadoCambiadoEvent evento = objectMapper.readValue(payload, PedidoEstadoCambiadoEvent.class);
            if (evento == null || evento.id() == null || evento.estadoNuevo() == null) {
                log.error("Skipping status event without order id or state: {}", payload);
                return;
            }
            // Only RECIBIDO -> EN_PREPARACION / DESPACHADO / ENTREGADO announce
            // themselves. A repeated or unknown state saves nothing and emails nothing.
            if (!EstadosPedido.enviaCorreo(evento.estadoNuevo())) {
                log.debug("State {} does not send an email, skipped (order {})",
                        evento.estadoNuevo(), evento.id());
                return;
            }

            // Idempotency guard: the PAIR, not the order id alone (S3)
            if (repository.existsByPedidoIdAndEstadoPedido(evento.id(), evento.estadoNuevo())) {
                log.debug("Duplicate status event for order {} in state {} skipped",
                        evento.id(), evento.estadoNuevo());
                return;
            }

            NotificacionEstado notificacion = construir(evento, extraerCodigoSeguimiento(payload));
            repository.save(notificacion);
            log.info("Status notification persisted for order {} in state {}",
                    evento.id(), evento.estadoNuevo());
            aplicarEnvioCorreo(notificacion);
        } catch (DataIntegrityViolationException e) {
            // Lost a race against a concurrent save of the same (pedidoId, estadoPedido)
            log.debug("Duplicate status event detected on save, skipped: {}", e.getMessage());
        } catch (Exception e) {
            // Poison pill: log honestly and continue so one bad message never
            // blocks the consumer or retries forever
            log.error("Could not process status event, skipping. Payload: {} - Reason: {}",
                    payload, e.getMessage());
        }
    }

    // Same hook as NotificacionListener: hand the row to the SMTP sender when one
    // is configured. EmailSenderService never throws, so this cannot break either.
    private void aplicarEnvioCorreo(NotificacionEstado notificacion) {
        if (!emailSenderService.habilitado()) {
            log.info("SMTP not configured, status notification stays PENDIENTE (order {}, {})",
                    notificacion.getPedidoId(), notificacion.getEstadoPedido());
            return;
        }
        boolean ok = emailSenderService.enviar(notificacion);
        notificacion.setEstadoEnvio(ok ? "ENVIADO" : "ERROR");
        try {
            repository.save(notificacion);
        } catch (Exception e) {
            log.error("Could not persist final state {} for order {}: {}",
                    notificacion.getEstadoEnvio(), notificacion.getPedidoId(), e.getMessage());
        }
    }

    // Builds the notification row from the event data plus the opaque tracking code
    private NotificacionEstado construir(PedidoEstadoCambiadoEvent evento, String codigoSeguimiento) {
        NotificacionEstado notificacion = new NotificacionEstado();
        notificacion.setPedidoId(evento.id());
        notificacion.setEstadoPedido(evento.estadoNuevo());
        notificacion.setEstadoAnterior(evento.estadoAnterior());
        notificacion.setCliente(valorPorDefecto(evento.cliente(), "cliente"));
        notificacion.setEmail(valorPorDefecto(evento.email(), "sin email"));
        notificacion.setAsunto("Pastelería My Dreams - Tu pedido: "
                + EstadosPedido.etiqueta(evento.estadoNuevo()));
        notificacion.setCuerpo(armarCuerpo(evento));
        notificacion.setCodigoSeguimiento(codigoSeguimiento);
        // Guardar productos serializados para renderizar en HTML
        try {
            if (evento.productos() != null) {
                notificacion.setProductosJson(objectMapper.writeValueAsString(evento.productos()));
            }
        } catch (Exception e) {
            log.debug("No se pudieron serializar productos para pedido {}: {}", evento.id(), e.getMessage());
        }
        notificacion.setEstadoEnvio("PENDIENTE");
        notificacion.setFecha(LocalDateTime.now());
        return notificacion;
    }

    // Plain-text fallback body. The HTML template takes the badge and the
    // sentence straight from the state code, so this only has to read well on
    // its own for mail clients without HTML.
    private String armarCuerpo(PedidoEstadoCambiadoEvent evento) {
        String cliente = valorPorDefecto(evento.cliente(), "cliente");
        String desde = EstadosPedido.etiqueta(evento.estadoAnterior());
        String hasta = EstadosPedido.etiqueta(evento.estadoNuevo());
        String total = evento.total() != null ? "$" + evento.total() : "sin dato";

        return String.format(
                "Hola %s,%n%n"
                        + "Tu pedido pasó de %s a %s.%n%n"
                        + "Estado actual: %s%n"
                        + "%s%n%n"
                        + "Total del pedido: %s%n",
                cliente, desde, hasta, hasta,
                EstadosPedido.mensaje(evento.estadoNuevo()),
                total);
    }

    // codigoConsulta rides along in the payload but the mirror record does not
    // model it, so it is read defensively from the raw JSON. Never throws.
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

    // Null/blank-safe helper so a partial event never crashes the listener
    private String valorPorDefecto(String valor, String porDefecto) {
        return (valor == null || valor.isBlank()) ? porDefecto : valor;
    }
}
