package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.event.PedidoEstadoCambiadoEvent;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.NotificacionEstado;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionEstadoRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

// Consume eventos PedidoEstadoCambiado del topic `pedidos-estados` y persiste
// UNA notificación por (pedido, estado) (S3). Cuando SMTP está configurado
// (MAIL_HOST definido) el correo de estado se envía justo después del guardado
// y la fila pasa a ENVIADO / ERROR; sin SMTP la fila queda PENDIENTE.
//
// Mismo contrato de listener que NotificacionListener: nunca revienta, tolera
// píldoras de veneno y salta duplicados. La única diferencia deliberada es la
// clave de idempotencia: acá es el PAR (pedidoId, estadoPedido), porque un
// mismo pedido recorre hasta tres estados y debe producir tres correos.
@Service
public class NotificacionEstadoListener {

    private static final Logger log = LoggerFactory.getLogger(NotificacionEstadoListener.class);

    // Grupo de consumidores separado del listener de confirmación de pedidos:
    // los dos consumen topics distintos y mantenerlos aparte hace que los
    // offsets sean independientes
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

    // Punto de entrada de cada mensaje del topic de estados. Política de errores
    // (rúbrica: éxito / falla recuperable / falla definitiva):
    //  - los duplicados los salta el control de idempotencia (nunca se lanzan)
    //  - las fallas definitivas (payload ilegible, evento sin id/estado) se
    //    relanzan y se aíslan en <topic>.DLT sin reintentos
    //  - las fallas transitorias se relanzan para que el manejador de errores
    //    las reintente con backoff y solo entonces las mueva a la DLQ
    @KafkaListener(topics = "${app.kafka.topic.estados:pedidos-estados}", groupId = GRUPO_CONSUMIDOR)
    public void onPedidoEstadoCambiado(String payload) {
        try {
            PedidoEstadoCambiadoEvent evento = objectMapper.readValue(payload, PedidoEstadoCambiadoEvent.class);
            if (evento == null || evento.id() == null || evento.estadoNuevo() == null) {
                log.error("Status event without order id or state, isolating to the DLQ: {}", payload);
                throw new IllegalArgumentException("status event without order id or state");
            }
            // Solo RECIBIDO -> EN_PREPARACION / DESPACHADO / ENTREGADO se
            // anuncian. Un estado repetido o desconocido no guarda nada y no manda nada.
            if (!EstadosPedido.enviaCorreo(evento.estadoNuevo())) {
                log.debug("State {} does not send an email, skipped (order {})",
                        evento.estadoNuevo(), evento.id());
                return;
            }

            // Control de idempotencia: el PAR, no solo el id del pedido (S3)
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
            // Se perdió una carrera contra un guardado concurrente del mismo (pedidoId, estadoPedido)
            log.debug("Duplicate status event detected on save, skipped: {}", e.getMessage());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Falla definitiva: evento ilegible o inválido -> sin reintentos, el
            // manejador de errores lo aísla en la DLQ para revisión manual
            log.error("Definitively invalid status message, isolating to the DLQ. Payload: {} - Reason: {}",
                    payload, e.getMessage());
            throw new IllegalArgumentException("invalid PedidoEstadoCambiado event", e);
        } catch (Exception e) {
            // Falla transitoria (base caída, infraestructura): se relanza para que
            // el manejador de errores la reintente y, si persiste, la aísle en la DLQ
            log.error("Recoverable failure processing status event. Payload: {} - Reason: {}",
                    payload, e.getMessage());
            throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e);
        }
    }

    // Mismo hook que NotificacionListener: entrega la fila al emisor SMTP cuando
    // hay uno configurado. EmailSenderService nunca lanza excepciones, así que
    // esto tampoco puede romper nada.
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

    // Construye la fila de notificación con los datos del evento más el código de seguimiento opaco
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

    // Cuerpo de respaldo en texto plano. La plantilla HTML toma el badge y la
    // frase directamente del código de estado, así que esto solo tiene que leerse
    // bien por sí solo en los clientes de correo sin HTML.
    private String armarCuerpo(PedidoEstadoCambiadoEvent evento) {
        String cliente = valorPorDefecto(evento.cliente(), "cliente");
        String desde = EstadosPedido.etiqueta(evento.estadoAnterior());
        String hasta = EstadosPedido.etiqueta(evento.estadoNuevo());
        String total = FormatoMoneda.clp(evento.total());

        StringBuilder cuerpo = new StringBuilder(String.format(
                "Hola %s,%n%n"
                        + "Tu pedido pasó de %s a %s.%n%n"
                        + "Estado actual: %s%n"
                        + "%s%n%n",
                cliente, desde, hasta, hasta,
                EstadosPedido.mensaje(evento.estadoNuevo())));

        // El correo de estado lista los precios de los productos junto al total
        // del pedido, así que un descuento debe explicitarse: sin esta línea el
        // cliente lee precios que no suman el total. El monto se deriva del
        // propio evento (los productos traen sus subtotales y el evento trae el
        // total final), así que el contrato de Kafka no tiene que cambiar.
        Integer descuento = calcularDescuento(evento);
        if (descuento != null) {
            cuerpo.append(String.format("Descuento aplicado: -%s%n",
                    FormatoMoneda.clp(descuento)));
        }

        return cuerpo.append(String.format("Total del pedido: %s%n", total)).toString();
    }

    // Lo que se le descontó al cliente: la suma de los subtotales de los
    // productos menos el total del pedido. Null cuando no hay nada que descontar
    // (sin productos, sin total, o el total ya coincide con los productos).
    private Integer calcularDescuento(PedidoEstadoCambiadoEvent evento) {
        if (evento.total() == null || evento.productos() == null || evento.productos().isEmpty()) {
            return null;
        }
        int suma = 0;
        for (PedidoEstadoCambiadoEvent.ProductoEstado producto : evento.productos()) {
            suma += (producto.subtotal() != null ? producto.subtotal() : 0);
        }
        int descuento = suma - evento.total();
        return descuento > 0 ? descuento : null;
    }

    // codigoConsulta viaja en el payload pero el record espejo no lo modela, así
    // que se lee de forma defensiva desde el JSON crudo. Nunca lanza excepciones.
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

    // Auxiliar a prueba de null/vacíos para que un evento parcial nunca reviente el listener
    private String valorPorDefecto(String valor, String porDefecto) {
        return (valor == null || valor.isBlank()) ? porDefecto : valor;
    }
}
