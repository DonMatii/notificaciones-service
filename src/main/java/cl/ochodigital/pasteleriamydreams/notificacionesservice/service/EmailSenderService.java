package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

// Sends the RF-09 order-confirmation email through the configured SMTP server.
// Enablement is decided ONLY by spring.mail.host: when it is blank the service
// reports itself as disabled and never touches the JavaMailSender, because Boot
// still creates the sender bean for an empty host.
@Service
public class EmailSenderService {

    private static final Logger log = LoggerFactory.getLogger(EmailSenderService.class);

    // Placeholder the listener writes when the event carries no customer email
    private static final String PLACEHOLDER_EMAIL = "sin email";

    // Resolved lazily: the JavaMailSender bean does not exist when spring.mail.host
    // is absent from the environment (e.g. the hermetic test classpath), and eager
    // injection would break context loading in that case.
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String host;
    private final String from;
    private final String username;

    public EmailSenderService(ObjectProvider<JavaMailSender> mailSenderProvider,
                              @Value("${spring.mail.host:}") String host,
                              @Value("${app.mail.from:}") String from,
                              @Value("${spring.mail.username:}") String username) {
        this.mailSenderProvider = mailSenderProvider;
        this.host = host;
        this.from = from;
        this.username = username;
    }

    // True only when an SMTP host is configured. Checked BEFORE any sender use.
    public boolean habilitado() {
        return host != null && !host.isBlank();
    }

    // Sends the already-built subject/body as UTF-8 plain text. Contract:
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
            MimeMessageHelper helper = new MimeMessageHelper(mensaje, StandardCharsets.UTF_8.name());
            helper.setFrom(remitente);
            helper.setTo(notificacion.getEmail().trim());
            helper.setSubject(notificacion.getAsunto());
            helper.setText(notificacion.getCuerpo());
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
}
