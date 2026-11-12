package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.event.SolicitudCreadaEvent;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.NotificacionSolicitud;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionSolicitudRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

// Consume los eventos SolicitudCreado del topic `solicitudes` y envía UN correo
// por solicitud a su autor (Versión 1, S2). Una solicitud no es una venta: el
// correo confirma que la pastelería recibió el pedido y que se contactará al
// cliente.
//
// Mismo contrato de listener que NotificacionEstadoListener: nunca lanza,
// tolera mensajes venenosos vía la DLQ y salta duplicados. La clave de
// idempotencia es el id de la solicitud: cada solicitud produce exactamente un
// correo, aunque Kafka reentregue el evento.
@Service
public class SolicitudListener {

    private static final Logger log = LoggerFactory.getLogger(SolicitudListener.class);

    // Grupo de consumidores propio: los tres listeners escuchan topics distintos
    // y mantener los grupos separados hace los offsets independientes
    private static final String GRUPO_CONSUMIDOR = "notificaciones-solicitudes";

    // Asunto con marca del acuse de la solicitud
    static final String ASUNTO_SOLICITUD = "Pastelería My Dreams — Recibimos tu solicitud";

    private final NotificacionSolicitudRepository repository;
    private final ObjectMapper objectMapper;
    private final EmailSenderService emailSenderService;

    public SolicitudListener(NotificacionSolicitudRepository repository,
                             ObjectMapper objectMapper,
                             EmailSenderService emailSenderService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.emailSenderService = emailSenderService;
    }

    // Punto de entrada de cada mensaje del topic de solicitudes. Política de
    // error (rúbrica: éxito / falla recuperable / falla definitiva):
    //  - los duplicados los salta el guardia de idempotencia (nunca se lanzan)
    //  - las fallas definitivas (payload ilegible, evento sin id) se relanzan
    //    y quedan aisladas en <topic>.DLT sin reintentos
    //  - las fallas transitorias se relanzan para que el manejador de errores
    //    reintente con backoff y recién después las mueva a la DLQ
    @KafkaListener(topics = "${app.kafka.topic.solicitudes:solicitudes}", groupId = GRUPO_CONSUMIDOR)
    public void onSolicitudCreada(String payload) {
        try {
            SolicitudCreadaEvent evento = objectMapper.readValue(payload, SolicitudCreadaEvent.class);
            if (evento == null || evento.id() == null) {
                log.error("Solicitud event without id, isolating to the DLQ: {}", payload);
                throw new IllegalArgumentException("solicitud event without id");
            }

            // Guardia de idempotencia: el id de la solicitud debe producir exactamente un correo
            if (repository.existsBySolicitudId(evento.id())) {
                log.debug("Duplicate event for solicitud {} skipped", evento.id());
                return;
            }

            NotificacionSolicitud notificacion = construir(evento);
            repository.save(notificacion);
            log.info("Solicitud notification persisted for solicitud {} with state PENDIENTE", evento.id());
            aplicarEnvioCorreo(notificacion);
        } catch (DataIntegrityViolationException e) {
            // Perdimos una carrera contra un guardado concurrente del mismo solicitudId
            log.debug("Duplicate event for solicitud detected on save, skipped: {}", e.getMessage());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Falla definitiva: evento ilegible o inválido → sin reintentos, el
            // manejador de errores lo envía directo a la DLQ para revisión manual
            log.error("Definitively invalid solicitud message, isolating to the DLQ. Payload: {} - Reason: {}",
                    payload, e.getMessage());
            throw new IllegalArgumentException("invalid SolicitudCreado event", e);
        } catch (Exception e) {
            // Falla transitoria (base de datos caída, infraestructura): se relanza
            // para que el manejador reintente y, si persiste, la aísle en la DLQ
            log.error("Recoverable failure processing solicitud event. Payload: {} - Reason: {}",
                    payload, e.getMessage());
            throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e);
        }
    }

    // Mismo enganche que los otros listeners: entregar la fila recién guardada al
    // emisor SMTP cuando hay uno configurado. EmailSenderService se traga sus
    // propios errores y nunca lanza, así el contrato del listener queda intacto.
    private void aplicarEnvioCorreo(NotificacionSolicitud notificacion) {
        if (!emailSenderService.habilitado()) {
            log.info("SMTP not configured, solicitud notification stays PENDIENTE (solicitud {})",
                    notificacion.getSolicitudId());
            return;
        }
        boolean ok = emailSenderService.enviarSolicitud(paraCorreo(notificacion));
        notificacion.setEstado(ok ? "ENVIADO" : "ERROR");
        try {
            repository.save(notificacion);
        } catch (Exception e) {
            log.error("Could not persist final state {} for solicitud {}: {}",
                    notificacion.getEstado(), notificacion.getSolicitudId(), e.getMessage());
        }
    }

    // EmailSenderService y el modelo Notificacion se reutilizan SIN MODIFICARLOS:
    // el correo se arma con una Notificacion transitoria (nunca persistida, porque
    // su columna única pedidoId chocaría con la secuencia de ids de pedidos en la
    // base compartida). pedidoId solo se usa en los logs del emisor.
    private Notificacion paraCorreo(NotificacionSolicitud fila) {
        Notificacion notificacion = new Notificacion();
        notificacion.setPedidoId(fila.getSolicitudId());
        notificacion.setCliente(fila.getNombre());
        notificacion.setEmail(fila.getEmail());
        notificacion.setAsunto(fila.getAsunto());
        notificacion.setCuerpo(fila.getCuerpo());
        return notificacion;
    }

    // Arma la fila de notificación con los datos del evento
    private NotificacionSolicitud construir(SolicitudCreadaEvent evento) {
        NotificacionSolicitud notificacion = new NotificacionSolicitud();
        notificacion.setSolicitudId(evento.id());
        notificacion.setNombre(valorPorDefecto(evento.nombre(), "cliente"));
        notificacion.setEmail(valorPorDefecto(evento.email(), "sin email"));
        notificacion.setAsunto(ASUNTO_SOLICITUD);
        notificacion.setCuerpo(armarCuerpo(evento));
        notificacion.setEstado("PENDIENTE");
        notificacion.setFecha(LocalDateTime.now());
        return notificacion;
    }

    // Cuerpo en texto plano: saludo por nombre, la solicitud, el mensaje si viene
    // y un cierre cercano. Las líneas "Tu solicitud:" y "Tu mensaje:" son las
    // etiquetas que el HTML de marca parsea para armar la tabla del resumen.
    private String armarCuerpo(SolicitudCreadaEvent evento) {
        String nombre = valorPorDefecto(evento.nombre(), "cliente");
        String detalle = valorPorDefecto(evento.detalle(), "sin detalle");

        StringBuilder cuerpo = new StringBuilder(String.format(
                "Hola %s,%n%n"
                        + "Recibimos tu solicitud de pedido en Pastelería My Dreams.%n%n"
                        + "Tu solicitud: %s%n%n",
                nombre,
                detalle));

        if (evento.mensaje() != null && !evento.mensaje().isBlank()) {
            cuerpo.append(String.format("Tu mensaje: %s%n%n", evento.mensaje().trim()));
        }

        return cuerpo.append(String.format(
                "Te contactaremos pronto para coordinar tu pedido.%n%n"
                        + "Gracias por confiar en Pastelería My Dreams.")).toString();
    }

    // Ayuda segura ante null o vacío para que un evento parcial nunca reviente el listener
    private String valorPorDefecto(String valor, String porDefecto) {
        return (valor == null || valor.isBlank()) ? porDefecto : valor;
    }
}
