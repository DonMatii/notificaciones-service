package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
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

// Sends the RF-09 order-confirmation email through the configured SMTP server.
// Enablement is decided ONLY by spring.mail.host: when it is blank the service
// reports itself as disabled and never touches the JavaMailSender, because Boot
// still creates the sender bean for an empty host.
//
// The message is a multipart/alternative (text/plain fallback + branded HTML
// with the logo inline by cid:), built at send time from the persisted row:
// cliente, cuerpo (plain text), codigoSeguimiento and asunto.
@Service
public class EmailSenderService {

    private static final Logger log = LoggerFactory.getLogger(EmailSenderService.class);

    // Placeholder the listener writes when the event carries no customer email
    private static final String PLACEHOLDER_EMAIL = "sin email";

    // Brand fallbacks used when app.mail.from-name / app.mail.site-url are blank
    private static final String MARCA_POR_DEFECTO = "Pastelería My Dreams";
    private static final String SITIO_POR_DEFECTO =
            "http://pasteleria-my-dreams-web-8digital.s3-website-us-east-1.amazonaws.com";
    private static final String RUTA_ESTADO = "/estado";

    // Template and inline logo, both on the classpath (src/main/resources/mail)
    private static final String PLANTILLA = "mail/pedido-recibido.html";
    private static final String LOGO = "mail/logo-pasteleria.png";
    private static final String CID_LOGO = "logo";

    // Labels written by NotificacionListener#armarCuerpo: the HTML summary is
    // parsed from them. If the format ever changes, resumenHtml degrades to a
    // <pre> block with the whole plain body instead of losing the content.
    private static final String ETIQUETA_PRODUCTOS = "Productos: ";
    private static final String ETIQUETA_CANTIDAD = "Cantidad total de articulos: ";
    private static final String ETIQUETA_TOTAL = "Total: ";

    // Resolved lazily: the JavaMailSender bean does not exist when spring.mail.host
    // is absent from the environment (e.g. the hermetic test classpath), and eager
    // injection would break context loading in that case.
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

    // True only when an SMTP host is configured. Checked BEFORE any sender use.
    public boolean habilitado() {
        return host != null && !host.isBlank();
    }

    // Sends the branded multipart message (text/plain + HTML, UTF-8). Contract:
    // returns true only on a confirmed send, false for skipped or failed
    // attempts, and never throws (the listener may not break either).
    public boolean enviar(Notificacion notificacion) {
        if (!habilitado()) {
            log.info("SMTP not configured (spring.mail.host is blank), email not sent for order {}",
                    notificacion != null ? notificacion.getPedidoId() : null);
            return false;
        }
        if (notificacion == null || !hasRealRecipient(notificacion.getEmail())) {
            // No real recipient: this is not a transport failure, just a skip
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
            JavaMailSender mailSender = mailSenderProvider.getObject();
            MimeMessage mensaje = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensaje,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
            // Display name + address in UTF-8: Gmail shows the brand, not "yo" (A4)
            helper.setFrom(new InternetAddress(remitente, nombreMarca(), StandardCharsets.UTF_8.name()));
            helper.setTo(notificacion.getEmail().trim());
            helper.setSubject(notificacion.getAsunto());
            // text/plain fallback + branded HTML inside a multipart/alternative;
            // the inline logo travels as a sibling within the related part
            helper.setText(textoPlano(notificacion), construirHtml(notificacion));
            helper.addInline(CID_LOGO, new ClassPathResource(LOGO));
            mailSender.send(mensaje);
            log.info("Email sent for order {} to {}", notificacion.getPedidoId(),
                    notificacion.getEmail());
            return true;
        } catch (Exception e) {
            log.error("Could not send email for order {}: {}",
                    notificacion.getPedidoId(), e.getMessage());
            return false;
        }
    }

    // app.mail.from wins; spring.mail.username is the fallback when it is blank
    private String resolveFrom() {
        if (from != null && !from.isBlank()) {
            return from;
        }
        return username;
    }

    // Blank or placeholder recipients are skipped, never counted as send attempts
    private boolean hasRealRecipient(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return !PLACEHOLDER_EMAIL.equalsIgnoreCase(email.trim());
    }

    // --- branded message composition ---------------------------------------

    // The persisted plain-text body, plus the tracking lines when a code exists
    private String textoPlano(Notificacion notificacion) {
        String base = notificacion.getCuerpo() != null ? notificacion.getCuerpo() : "";
        String codigo = codigoDe(notificacion);
        if (codigo == null) {
            return base;
        }
        return base + "\n\nCodigo de seguimiento: " + codigo
                + "\nConsultalo en: " + urlEstado();
    }

    // Fills the HTML template from the row: logo is already cid: in the file
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

    // Order summary as a small table parsed from the three fixed labels of the
    // plain body. Defensive fallback: embed the whole body in a <pre> block.
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

    // One row of the summary table (skipped when the label is missing)
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

    // Value of one fixed label in the plain body, or null when absent
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

    // Tracking block: the opaque code as copyable text plus a button to the
    // plain /estado route (the public page does not read query params, so the
    // code is NOT appended to the URL). Null/blank code => block omitted entirely.
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

    // Plain /estado URL of the public site, trailing slashes removed
    private String urlEstado() {
        String sitio = (siteUrl == null || siteUrl.isBlank()) ? SITIO_POR_DEFECTO : siteUrl.trim();
        while (sitio.endsWith("/")) {
            sitio = sitio.substring(0, sitio.length() - 1);
        }
        return sitio + RUTA_ESTADO;
    }

    // Brand display name for From header and signature
    private String nombreMarca() {
        return (fromName == null || fromName.isBlank()) ? MARCA_POR_DEFECTO : fromName.trim();
    }

    // Tracking code of the row, normalized to null when absent/blank
    private String codigoDe(Notificacion notificacion) {
        String codigo = notificacion.getCodigoSeguimiento();
        if (codigo == null || codigo.isBlank()) {
            return null;
        }
        return codigo.trim();
    }

    // The listener stores a default label when the event had no name
    private String valorCliente(Notificacion notificacion) {
        String cliente = notificacion.getCliente();
        return (cliente == null || cliente.isBlank()) ? "cliente" : cliente;
    }

    // Minimal HTML escaping: customer data must never inject markup (XSS)
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
