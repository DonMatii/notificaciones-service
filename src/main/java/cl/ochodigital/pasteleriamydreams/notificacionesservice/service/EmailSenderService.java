package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.NotificacionEstado;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

// Envía el correo de confirmación de pedido (RF-09) a través del servidor SMTP
// configurado. La habilitación se decide SOLO por spring.mail.host: cuando está
// en blanco el servicio se reporta como deshabilitado y nunca toca el
// JavaMailSender, porque Boot igual crea el bean del emisor con un host vacío.
//
// El mensaje es un multipart/alternative (respaldo text/plain + HTML con la
// marca y el logo inline por cid:), construido al momento de enviar desde la
// fila persistida: cliente, cuerpo (texto plano), codigoSeguimiento y asunto.
@Service
public class EmailSenderService {

    private static final Logger log = LoggerFactory.getLogger(EmailSenderService.class);

    // Placeholder que escribe el listener cuando el evento no trae email del cliente
    private static final String PLACEHOLDER_EMAIL = "sin email";

    // Valores por defecto de la marca, usados cuando app.mail.from-name / app.mail.site-url están en blanco
    private static final String MARCA_POR_DEFECTO = "Pastelería My Dreams";
    private static final String SITIO_POR_DEFECTO =
            "http://pasteleria-my-dreams-web-8digital.s3-website-us-east-1.amazonaws.com";
    private static final String RUTA_ESTADO = "/estado";

    // Plantilla y logo inline, ambos en el classpath (src/main/resources/mail)
    private static final String PLANTILLA = "mail/pedido-recibido.html";
    private static final String PLANTILLA_SOLICITUD = "mail/solicitud-recibida.html";
    // Plantilla para los correos de cambio de estado (S4): mismo envoltorio y mismo
    // logo inline, pero el titular es el estado nuevo en vez del resumen del pedido
    private static final String PLANTILLA_ESTADO = "mail/pedido-estado.html";
    private static final String LOGO = "mail/logo-pasteleria.png";
    private static final String CID_LOGO = "logo";

    // Etiquetas que escribe NotificacionListener#armarCuerpo: de ellas se parsea
    // el resumen HTML. Si el formato cambia alguna vez, resumenHtml degrada a un
    // bloque <pre> con todo el cuerpo en texto plano en vez de perder el contenido.
    private static final String ETIQUETA_PRODUCTOS = "Productos: ";
    private static final String ETIQUETA_CANTIDAD = "Cantidad total de articulos: ";
    private static final String ETIQUETA_TOTAL = "Total: ";
    // Etiqueta que escribe NotificacionEstadoListener#armarCuerpo para los correos de estado
    private static final String ETIQUETA_TOTAL_ESTADO = "Total del pedido: ";
    private static final String ETIQUETA_DESCUENTO = "Descuento aplicado: ";
    private static final String ETIQUETA_SOLICITUD = "Tu solicitud: ";
    private static final String ETIQUETA_MENSAJE = "Tu mensaje: ";
    private static final String RUTA_DELICIAS = "/delicias";

    // Resuelto de forma lazy: el bean JavaMailSender no existe cuando spring.mail.host
    // está ausente del entorno (p. ej. el classpath hermético de tests), y una
    // inyección eager rompería la carga del contexto en ese caso.
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String host;
    private final String from;
    private final String username;
    private final String fromName;
    private final String siteUrl;

    public EmailSenderService(ObjectProvider<JavaMailSender> mailSenderProvider,
                              @Value("${spring.mail.host:}") String host,
                              @Value("${app.mail.from:}") String from,
                              @Value("${spring.mail.username:}") String username,
                              @Value("${app.mail.from-name:Pastelería My Dreams}") String fromName,
                              @Value("${app.mail.site-url:http://pasteleria-my-dreams-web-8digital.s3-website-us-east-1.amazonaws.com}") String siteUrl) {
        this.mailSenderProvider = mailSenderProvider;
        this.host = host;
        this.from = from;
        this.username = username;
        this.fromName = fromName;
        this.siteUrl = siteUrl;
    }

    // True solo cuando hay un host SMTP configurado. Se verifica ANTES de cualquier uso del emisor.
    public boolean habilitado() {
        return host != null && !host.isBlank();
    }

    // Envía el mensaje multipart con la marca (text/plain + HTML, UTF-8). Contrato:
    // devuelve true solo ante un envío confirmado, false ante intentos salteados o
    // fallidos, y nunca lanza excepciones (el listener tampoco puede romperse).
    public boolean enviar(Notificacion notificacion) {
        if (!habilitado()) {
            log.info("SMTP not configured (spring.mail.host is blank), email not sent for order {}",
                    notificacion != null ? notificacion.getPedidoId() : null);
            return false;
        }
        if (notificacion == null || !hasRealRecipient(notificacion.getEmail())) {
            // Sin destinatario real: esto no es una falla de transporte, solo un salteo
            log.info("Skipping email for order {}: no real recipient ({})",
                    notificacion != null ? notificacion.getPedidoId() : null,
                    notificacion != null ? notificacion.getEmail() : null);
            return false;
        }

        String remitente = resolveFrom();
        if (remitente == null || remitente.isBlank()) {
            log.error("Cannot send email for order {}: neither app.mail.from nor "
                    + "spring.mail.username is configured", notificacion.getPedidoId());
            return false;
        }

        try {
            return transportar(remitente, notificacion.getEmail().trim(), notificacion.getAsunto(),
                    textoPlano(notificacion), construirHtml(notificacion),
                    "Email sent for order {} to {}", notificacion.getPedidoId(), notificacion.getEmail());
        } catch (Exception e) {
            log.error("Could not send email for order {}: {}",
                    notificacion.getPedidoId(), e.getMessage());
            return false;
        }
    }

    // Correo de cambio de estado (S4). Mismo transporte, mismo envoltorio con la
    // marca y mismo contrato que enviar(Notificacion): habilitación decidida solo
    // por spring.mail.host, destinatarios en blanco salteados sin contar como
    // intento, nunca lanza excepciones y true solo ante un envío confirmado. Lo que
    // cambia es la plantilla y que el titular es el estado al que el cliente está PASANDO.
    public boolean enviar(NotificacionEstado notificacion) {
        if (!habilitado()) {
            log.info("SMTP not configured (spring.mail.host is blank), status email not sent for order {}",
                    notificacion != null ? notificacion.getPedidoId() : null);
            return false;
        }
        if (notificacion == null || !hasRealRecipient(notificacion.getEmail())) {
            log.info("Skipping status email for order {}: no real recipient ({})",
                    notificacion != null ? notificacion.getPedidoId() : null,
                    notificacion != null ? notificacion.getEmail() : null);
            return false;
        }

        String remitente = resolveFrom();
        if (remitente == null || remitente.isBlank()) {
            log.error("Cannot send status email for order {}: neither app.mail.from nor "
                    + "spring.mail.username is configured", notificacion.getPedidoId());
            return false;
        }

        try {
            return transportar(remitente, notificacion.getEmail().trim(), notificacion.getAsunto(),
                    textoPlano(notificacion), construirHtml(notificacion),
                    "Status email sent for order {} ({}) to {}", notificacion.getPedidoId(),
                    notificacion.getEstadoPedido(), notificacion.getEmail());
        } catch (Exception e) {
            log.error("Could not send status email for order {}: {}",
                    notificacion.getPedidoId(), e.getMessage());
            return false;
        }
    }

    // Único transporte multipart (text/plain + HTML con logo inline) que comparten
    // los tres correos: solo cambian el texto plano, el HTML y las etiquetas del
    // log. Devuelve true solo ante un envío confirmado y jamás lanza.
    private boolean transportar(String remitente, String destinatario, String asunto,
            String textoPlano, String html, String logOk, Object... argsLog) throws Exception {
        JavaMailSender mailSender = mailSenderProvider.getObject();
        MimeMessage mensaje = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mensaje,
                MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
        // Nombre a mostrar y dirección en UTF-8: Gmail muestra la marca, no "yo" (A4)
        helper.setFrom(new InternetAddress(remitente, nombreMarca(), StandardCharsets.UTF_8.name()));
        helper.setTo(destinatario);
        helper.setSubject(asunto);
        // Respaldo text/plain + HTML con marca dentro de un multipart/alternative;
        // el logo inline viaja como hermano dentro de la parte related
        helper.setText(textoPlano, html);
        helper.addInline(CID_LOGO, new ClassPathResource(LOGO));
        mailSender.send(mensaje);
        log.info(logOk, argsLog);
        return true;
    }

    // --- Solicitud de pedido (Versión 1) -------------------------------------

    // Correo de la solicitud: mismo transporte y mismo contrato que
    // enviar(Notificacion) (habilitado solo por spring.mail.host, destinatarios
    // reales, nunca lanza), pero con su propia plantilla: los textos fijos
    // hablan de una SOLICITUD, no de un pedido en preparación.
    public boolean enviarSolicitud(Notificacion notificacion) {
        if (!habilitado()) {
            log.info("SMTP not configured (spring.mail.host is blank), solicitud email not sent for {}",
                    notificacion != null ? notificacion.getPedidoId() : null);
            return false;
        }
        if (notificacion == null || !hasRealRecipient(notificacion.getEmail())) {
            log.info("Skipping solicitud email for {}: no real recipient ({})",
                    notificacion != null ? notificacion.getPedidoId() : null,
                    notificacion != null ? notificacion.getEmail() : null);
            return false;
        }
        String remitente = resolveFrom();
        if (remitente == null || remitente.isBlank()) {
            log.error("Cannot send solicitud email for {}: neither app.mail.from nor "
                    + "spring.mail.username is configured", notificacion.getPedidoId());
            return false;
        }
        try {
            return transportar(remitente, notificacion.getEmail().trim(), notificacion.getAsunto(),
                    textoPlanoSolicitud(notificacion), construirHtmlSolicitud(notificacion),
                    "Solicitud email sent for {} to {}",
                    notificacion.getPedidoId(), notificacion.getEmail());
        } catch (Exception e) {
            log.error("Could not send solicitud email for {}: {}",
                    notificacion.getPedidoId(), e.getMessage());
            return false;
        }
    }

    // Texto plano de la solicitud: el cuerpo completo tal como lo armó el listener
    // (saludo por nombre, detalle, mensaje opcional y cierre cercano)
    private String textoPlanoSolicitud(Notificacion notificacion) {
        return notificacion.getCuerpo() != null ? notificacion.getCuerpo() : "";
    }

    // Plantilla de solicitud: saludo con nombre, resumen en tabla etiqueta/valor
    // (mismo lenguaje visual que el correo de pedido) e invitación al catálogo.
    // Sin textos de pedido: el correo habla de una SOLICITUD.
    private String construirHtmlSolicitud(Notificacion notificacion) throws Exception {
        String plantilla;
        try (var entrada = new ClassPathResource(PLANTILLA_SOLICITUD).getInputStream()) {
            plantilla = new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        }
        String cliente = notificacion.getCliente();
        return plantilla
                .replace("{{marca}}", escapar(nombreMarca()))
                .replace("{{cliente}}", escapar(cliente == null || cliente.isBlank() ? "cliente" : cliente))
                .replace("{{resumen}}", tablaSolicitud(notificacion.getCuerpo()))
                .replace("{{cta}}", bloqueCtaSolicitud());
    }

    // Tabla etiqueta/valor con lo que el cliente pidió, parseada del cuerpo con
    // el mismo mecanismo de etiquetas fijas que ya usa el resumen del pedido
    private String tablaSolicitud(String cuerpo) {
        StringBuilder tabla = new StringBuilder(
                "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                        + "style=\"border-collapse:collapse;\">");
        String detalle = extraerEtiqueta(cuerpo, ETIQUETA_SOLICITUD);
        filaResumen(tabla, "Solicitud", detalle != null ? detalle : "—");
        String mensaje = extraerEtiqueta(cuerpo, ETIQUETA_MENSAJE);
        if (mensaje != null) {
            filaResumen(tabla, "Mensaje", mensaje);
        }
        return tabla.append("</table>").toString();
    }

    // Botón rosa hacia el catálogo: mismo lenguaje visual que el bloque de
    // seguimiento del correo de pedido
    private String bloqueCtaSolicitud() {
        String url = escapar(urlDelicias());
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-top:28px;\">"
                + "<tr><td align=\"center\">"
                + "<a href=\"" + url + "\" style=\"background:#e75480;color:#ffffff;text-decoration:none;"
                + "padding:14px 32px;border-radius:8px;font-size:16px;font-weight:bold;display:inline-block;\">"
                + "Ver nuestras delicias</a>"
                + "<p style=\"margin:14px 0 0;font-size:13px;color:#8a6d7b;\">También puedes explorar todo el "
                + "catálogo en <a href=\"" + url + "\" style=\"color:#e75480;\">" + url + "</a></p>"
                + "</td></tr></table>";
    }

    // Respaldo en texto plano para el correo de estado: el cuerpo persistido más
    // las líneas de seguimiento cuando hay código. Espejo de textoPlano(Notificacion).
    private String textoPlano(NotificacionEstado notificacion) {
        String base = notificacion.getCuerpo() != null ? notificacion.getCuerpo() : "";
        String codigo = notificacion.getCodigoSeguimiento();
        if (codigo == null || codigo.isBlank()) {
            return base;
        }
        return base + "\n\nCodigo de seguimiento: " + codigo.trim()
                + "\nConsultalo en: " + urlEstado();
    }

    // Rellena la plantilla de estado. A diferencia del correo de confirmación, el
    // titular y la frase salen directo del código de estado vía EstadosPedido, así
    // que la redacción nunca puede divergir de lo que el listener escribió en el
    // cuerpo en texto plano.
    private String construirHtml(NotificacionEstado notificacion) throws Exception {
        String plantilla;
        try (var entrada = new ClassPathResource(PLANTILLA_ESTADO).getInputStream()) {
            plantilla = new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        }
        String cliente = notificacion.getCliente();
        return plantilla
                .replace("{{marca}}", escapar(nombreMarca()))
                .replace("{{cliente}}", escapar(cliente == null || cliente.isBlank() ? "cliente" : cliente))
                .replace("{{estado}}", escapar(EstadosPedido.etiqueta(notificacion.getEstadoPedido())))
                .replace("{{mensaje}}", escapar(EstadosPedido.mensaje(notificacion.getEstadoPedido())))
                .replace("{{productos}}", tablaProductos(notificacion))
                .replace("{{resumen}}", resumenTotal(notificacion))
                .replace("{{seguimiento}}", bloqueSeguimiento(notificacion.getCodigoSeguimiento()));
    }

    // Una tabla chica con el total del pedido, parseada del cuerpo en texto plano
    // con el mismo mecanismo de etiquetas que ya usa el correo de confirmación.
    // Una etiqueta ausente degrada a "sin tabla", nunca a un layout roto.
    private String resumenTotal(NotificacionEstado notificacion) {
        String total = extraerEtiqueta(notificacion.getCuerpo(), ETIQUETA_TOTAL_ESTADO);
        if (total == null) {
            return "";
        }
        StringBuilder tabla = new StringBuilder(
                "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                        + "style=\"border-collapse:collapse;\">");
        // La línea de descuento solo existe cuando el pedido tuvo uno: las
        // notificaciones persistidas antes de este cambio se siguen renderizando
        // exactamente igual.
        String descuento = extraerEtiqueta(notificacion.getCuerpo(), ETIQUETA_DESCUENTO);
        if (descuento != null) {
            filaResumen(tabla, "Descuento aplicado", descuento);
        }
        filaResumen(tabla, "Total del pedido", total);
        return tabla.append("</table>").toString();
    }

    // Tabla de productos desglosados a partir del JSON serializado en la notificación
    private String tablaProductos(NotificacionEstado notificacion) {
        String json = notificacion.getProductosJson();
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode array = mapper.readTree(json);
            if (!array.isArray() || array.size() == 0) {
                return "";
            }
            StringBuilder tabla = new StringBuilder(
                    "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" " +
                    "style=\"border-collapse:collapse;margin:20px 0;\">");
            tabla.append("<tr><th align=\"left\" style=\"padding:8px 0;border-bottom:2px solid #e75480;font-family:Arial,sans-serif;font-size:13px;color:#2b2b2b;\">Producto</th>");
            tabla.append("<th align=\"right\" style=\"padding:8px 0;border-bottom:2px solid #e75480;font-family:Arial,sans-serif;font-size:13px;color:#2b2b2b;\">Cant.</th>");
            tabla.append("<th align=\"right\" style=\"padding:8px 0;border-bottom:2px solid #e75480;font-family:Arial,sans-serif;font-size:13px;color:#2b2b2b;\">Precio</th>");
            tabla.append("<th align=\"right\" style=\"padding:8px 0;border-bottom:2px solid #e75480;font-family:Arial,sans-serif;font-size:13px;color:#2b2b2b;\">Subtotal</th></tr>");
            for (com.fasterxml.jackson.databind.JsonNode nodo : array) {
                String nombre = nodo.path("nombre").asText("");
                int cantidad = nodo.path("cantidad").asInt(0);
                int precio = nodo.path("precioUnitario").asInt(0);
                int subtotal = nodo.path("subtotal").asInt(0);
                tabla.append("<tr>");
                tabla.append("<td style=\"padding:8px 0;font-family:Arial,sans-serif;font-size:14px;color:#4a4a4a;\">").append(escapar(nombre)).append("</td>");
                tabla.append("<td align=\"right\" style=\"padding:8px 0;font-family:Arial,sans-serif;font-size:14px;color:#4a4a4a;\">").append(cantidad).append("</td>");
                tabla.append("<td align=\"right\" style=\"padding:8px 0;font-family:Arial,sans-serif;font-size:14px;color:#4a4a4a;\">").append(FormatoMoneda.clp(precio)).append("</td>");
                tabla.append("<td align=\"right\" style=\"padding:8px 0;font-family:Arial,sans-serif;font-size:14px;color:#2b2b2b;font-weight:bold;\">").append(FormatoMoneda.clp(subtotal)).append("</td>");
                tabla.append("</tr>");
            }
            tabla.append("</table>");
            return tabla.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // app.mail.from tiene prioridad; spring.mail.username es el respaldo cuando está en blanco
    private String resolveFrom() {
        if (from != null && !from.isBlank()) {
            return from;
        }
        return username;
    }

    // Los destinatarios en blanco o placeholder se saltan, nunca cuentan como intentos de envío
    private boolean hasRealRecipient(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return !PLACEHOLDER_EMAIL.equalsIgnoreCase(email.trim());
    }

    // --- composición del mensaje con la marca -------------------------------

    // El cuerpo persistido en texto plano, más las líneas de seguimiento cuando hay código
    private String textoPlano(Notificacion notificacion) {
        String base = notificacion.getCuerpo() != null ? notificacion.getCuerpo() : "";
        String codigo = codigoDe(notificacion);
        if (codigo == null) {
            return base;
        }
        return base + "\n\nCodigo de seguimiento: " + codigo
                + "\nConsultalo en: " + urlEstado();
    }

    // Rellena la plantilla HTML desde la fila: el logo ya viene cid: en el archivo
    private String construirHtml(Notificacion notificacion) throws Exception {
        String plantilla;
        try (var entrada = new ClassPathResource(PLANTILLA).getInputStream()) {
            plantilla = new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        }
        return plantilla
                .replace("{{marca}}", escapar(nombreMarca()))
                .replace("{{cliente}}", escapar(valorCliente(notificacion)))
                .replace("{{resumen}}", resumenHtml(notificacion.getCuerpo()))
                .replace("{{seguimiento}}", bloqueSeguimiento(codigoDe(notificacion)));
    }

    // Resumen del pedido como tabla chica, parseada de las tres etiquetas fijas
    // del cuerpo en texto plano. Respaldo defensivo: incrusta todo el cuerpo en
    // un bloque <pre>.
    private String resumenHtml(String cuerpo) {
        String productos = extraerEtiqueta(cuerpo, ETIQUETA_PRODUCTOS);
        String cantidad = extraerEtiqueta(cuerpo, ETIQUETA_CANTIDAD);
        String total = extraerEtiqueta(cuerpo, ETIQUETA_TOTAL);
        if (productos == null && cantidad == null && total == null) {
            return "<pre style=\"margin:0;white-space:pre-wrap;font-family:Arial,sans-serif;"
                    + "font-size:14px;color:#4a4a4a;\">" + escapar(cuerpo != null ? cuerpo : "")
                    + "</pre>";
        }
        StringBuilder tabla = new StringBuilder(
                "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                        + "style=\"border-collapse:collapse;\">");
        filaResumen(tabla, "Productos", productos);
        filaResumen(tabla, "Articulos", cantidad);
        filaResumen(tabla, "Total", total);
        return tabla.append("</table>").toString();
    }

    // Una fila de la tabla de resumen (se salta cuando falta la etiqueta)
    private void filaResumen(StringBuilder tabla, String etiqueta, String valor) {
        if (valor == null) {
            return;
        }
        tabla.append("<tr>")
                .append("<td style=\"padding:8px 0;border-bottom:1px solid #f0e0e6;"
                        + "font-family:Arial,sans-serif;font-size:14px;color:#6b6b6b;\">")
                .append(escapar(etiqueta))
                .append("</td>")
                .append("<td align=\"right\" style=\"padding:8px 0;border-bottom:1px solid #f0e0e6;"
                        + "font-family:Arial,sans-serif;font-size:14px;color:#2b2b2b;font-weight:bold;\">")
                .append(escapar(valor))
                .append("</td>")
                .append("</tr>");
    }

    // Valor de una etiqueta fija del cuerpo en texto plano, o null si no está
    private String extraerEtiqueta(String cuerpo, String etiqueta) {
        if (cuerpo == null) {
            return null;
        }
        for (String linea : cuerpo.split("\\R")) {
            if (linea.startsWith(etiqueta)) {
                String valor = linea.substring(etiqueta.length()).trim();
                return valor.isEmpty() ? null : valor;
            }
        }
        return null;
    }

    // Bloque de seguimiento: el código opaco como texto copiable más un botón a la
    // ruta /estado plana (la página pública no lee query params, así que el código
    // NO se agrega a la URL). Código null/en blanco => el bloque se omite completo.
    private String bloqueSeguimiento(String codigo) {
        if (codigo == null) {
            return "";
        }
        String url = escapar(urlEstado());
        return """
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" \
                style="margin:24px 0;background:#fdf3f7;border:1px solid #f3d6e2;border-radius:8px;">
                  <tr><td align="center" style="padding:20px 24px;">
                    <p style="margin:0 0 6px;font-family:Arial,sans-serif;font-size:13px; \
                color:#8a6d7b;text-transform:uppercase;letter-spacing:1px;">Código de seguimiento</p>
                    <p style="margin:0 0 16px;font-family:Courier New,monospace;font-size:18px; \
                color:#2b2b2b;font-weight:bold;">{{codigo}}</p>
                    <p style="margin:0 0 8px;"><a href="{{url}}" style="background:#e75480; \
                color:#ffffff;padding:12px 24px;border-radius:6px;text-decoration:none; \
                font-family:Arial,sans-serif;font-size:15px;font-weight:bold;display:inline-block;"> \
                Consultar estado del pedido</a></p>
                    <p style="margin:0;font-family:Arial,sans-serif;font-size:12px;color:#8a6d7b;"> \
                También puedes copiar tu código y pegarlo en {{url}}</p>
                  </td></tr>
                </table>
                """
                .replace("{{codigo}}", escapar(codigo))
                .replace("{{url}}", url);
    }

    // URL pública /estado del sitio, sin barras finales
    private String urlEstado() {
        return sitioBase() + RUTA_ESTADO;
    }

    // URL pública del catálogo del sitio
    private String urlDelicias() {
        return sitioBase() + RUTA_DELICIAS;
    }

    // URL base del sitio, sin barras finales
    private String sitioBase() {
        String sitio = (siteUrl == null || siteUrl.isBlank()) ? SITIO_POR_DEFECTO : siteUrl.trim();
        while (sitio.endsWith("/")) {
            sitio = sitio.substring(0, sitio.length() - 1);
        }
        return sitio;
    }

    // Nombre a mostrar de la marca para el header From y la firma
    private String nombreMarca() {
        return (fromName == null || fromName.isBlank()) ? MARCA_POR_DEFECTO : fromName.trim();
    }

    // Código de seguimiento de la fila, normalizado a null cuando está ausente o en blanco
    private String codigoDe(Notificacion notificacion) {
        String codigo = notificacion.getCodigoSeguimiento();
        if (codigo == null || codigo.isBlank()) {
            return null;
        }
        return codigo.trim();
    }

    // El listener guarda una etiqueta por defecto cuando el evento no traía nombre
    private String valorCliente(Notificacion notificacion) {
        String cliente = notificacion.getCliente();
        return (cliente == null || cliente.isBlank()) ? "cliente" : cliente;
    }

    // Escape HTML mínimo: los datos del cliente nunca deben inyectar markup (XSS)
    private String escapar(String valor) {
        if (valor == null) {
            return "";
        }
        return valor.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
