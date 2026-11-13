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

// Consume eventos PedidoCreado del topic `pedidos` y persiste una notificación
// por pedido (RF-09). Cuando SMTP está configurado (MAIL_HOST definido) el
// correo de confirmación se envía justo después del guardado y la fila pasa a
// ENVIADO / ERROR; sin SMTP la fila queda PENDIENTE, exactamente como antes.
@Service
public class NotificacionListener {

    private static final Logger log = LoggerFactory.getLogger(NotificacionListener.class);

    // El grupo de consumidores al que pertenece este servicio
    private static final String GRUPO_CONSUMIDOR = "notificaciones";

    // Asunto con la marca, guardado en la fila y enviado en cada correo de
    // confirmación: solo la marca, nunca el id secuencial interno (RF-11 lo
    // mantiene en privado)
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

    // Punto de entrada de cada mensaje del topic. Política de errores (rúbrica:
    // éxito / falla recuperable / falla definitiva):
    //  - los duplicados los salta el control de idempotencia (nunca se lanzan)
    //  - las fallas definitivas (payload ilegible, evento sin id) se relanzan y
    //    el manejador de errores las aísla en <topic>.DLT sin reintentos: nada
    //    se pierde en silencio
    //  - las fallas transitorias (base caída, infraestructura) también se
    //    relanzan: el manejador de errores reintenta con backoff y solo entonces
    //    mueve el mensaje a la DLQ
    @KafkaListener(topics = "${app.kafka.topic:pedidos}", groupId = GRUPO_CONSUMIDOR)
    public void onPedidoCreado(String payload) {
        try {
            PedidoCreadoEvent evento = objectMapper.readValue(payload, PedidoCreadoEvent.class);
            if (evento == null || evento.id() == null) {
                log.error("Event without order id, isolating to the DLQ: {}", payload);
                throw new IllegalArgumentException("event without order id");
            }

            // Control de idempotencia: un mismo pedidoId debe producir exactamente una fila
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
            // Se perdió una carrera contra un guardado concurrente del mismo pedidoId
            log.debug("Duplicate event for order detected on save, skipped: {}", e.getMessage());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Falla definitiva: evento ilegible o inválido -> sin reintentos, el
            // manejador de errores lo envía directo a la DLQ para revisión manual
            log.error("Definitively invalid message, isolating to the DLQ. Payload: {} - Reason: {}",
                    payload, e.getMessage());
            throw new IllegalArgumentException("invalid PedidoCreado event", e);
        } catch (Exception e) {
            // Falla transitoria (base caída, infraestructura): se relanza para que
            // el manejador de errores reintente y, si persiste, lo aísle en la
            // DLQ. El viejo "registrar y seguir" perdía en silencio el correo de
            // confirmación del pedido.
            log.error("Recoverable failure processing event. Payload: {} - Reason: {}",
                    payload, e.getMessage());
            throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e);
        }
    }

    // RF-09: entrega la fila recién guardada al emisor SMTP cuando hay uno
    // configurado. Se ejecuta después del control de idempotencia y el guardado.
    // EmailSenderService absorbe sus propios errores y nunca lanza excepciones,
    // por lo que el contrato "el listener nunca revienta" y el manejo de
    // píldoras de veneno de arriba quedan intactos.
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

    // Construye la fila de notificación con los datos del evento más el código
    // de seguimiento opaco (nullable: los eventos publicados antes de RF-11 no
    // lo traen)
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

    // codigoConsulta (RF-11) viaja en el payload pero el record espejo no lo
    // modela, así que se lee de forma defensiva desde el JSON crudo. Nunca
    // lanza excepciones: un valor ausente o raro simplemente degrada a null.
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

    // Cuerpo en texto plano con cliente / producto / cantidad / total del evento
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

    // Auxiliar a prueba de null/vacíos para que un evento parcial nunca reviente el listener
    private String valorPorDefecto(String valor, String porDefecto) {
        return (valor == null || valor.isBlank()) ? porDefecto : valor;
    }
}
