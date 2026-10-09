package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Unit tests for the RF-09 SMTP sender:
//  - enablement depends ONLY on spring.mail.host being non-blank, and the
//    JavaMailSender is never touched when SMTP is not configured (Boot still
//    creates the bean for an empty host, so the guard must come first);
//  - every failure is swallowed and reported as false so the Kafka listener
//    contract (never throws) still holds;
//  - blank / placeholder recipients are skipped, not counted as send attempts.
class EmailSenderServiceTest {

    private JavaMailSender mailSender;
    private ObjectProvider<JavaMailSender> mailSenderProvider;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        mailSenderProvider = mock(ObjectProvider.class);
        when(mailSenderProvider.getObject()).thenReturn(mailSender);
    }

    private EmailSenderService service(String host, String from, String username) {
        // from-name and site-url use their production defaults for this suite
        return new EmailSenderService(mailSenderProvider, host, from, username,
                "Pastelería My Dreams", "https://tienda.test");
    }

    private Notificacion notificacion(String email) {
        Notificacion n = new Notificacion();
        n.setPedidoId(101L);
        n.setEmail(email);
        n.setAsunto("Pastelería My Dreams — Recibimos tu pedido");
        n.setCuerpo("Hola Daniela,");
        return n;
    }

    // First text/plain part of the (now multipart) message, null when absent
    private static String textoPlano(jakarta.mail.Part parte) throws Exception {
        Object contenido = parte.getContent();
        if (contenido instanceof jakarta.mail.Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String resultado = textoPlano(mp.getBodyPart(i));
                if (resultado != null) {
                    return resultado;
                }
            }
            return null;
        }
        return parte.isMimeType("text/plain") ? contenido.toString() : null;
    }

    @Test
    void blankHostDisablesSendingWithoutTouchingTheSender() {
        EmailSenderService s = service("", "", "");

        assertFalse(s.habilitado(), "An empty spring.mail.host must disable the sender");
        assertFalse(s.enviar(notificacion("daniela@ejemplo.cl")),
                "enviar must return false when SMTP is not configured");
        verifyNoInteractions(mailSender);
    }

    @Test
    void configuredHostSendsMimeMessageToRecipientWithSubject() throws Exception {
        MimeMessage mensaje = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mensaje);
        EmailSenderService s = service("smtp.example.test", "no-reply@ejemplo.cl", "");

        assertTrue(s.habilitado(), "A non-blank spring.mail.host must enable the sender");
        assertTrue(s.enviar(notificacion("daniela@ejemplo.cl")));

        verify(mailSender).send(mensaje);
        assertEquals("daniela@ejemplo.cl",
                mensaje.getRecipients(Message.RecipientType.TO)[0].toString());
        assertEquals("Pastelería My Dreams — Recibimos tu pedido", mensaje.getSubject());
        // The body now travels as a multipart/alternative: the text/plain
        // fallback must still carry the customer-facing text
        assertTrue(mensaje.getContent() instanceof jakarta.mail.Multipart,
                "The message must be multipart after RF-09 branding");
        String texto = textoPlano(mensaje);
        assertTrue(texto != null && texto.contains("Hola Daniela"),
                "The plain part must carry the customer-facing text");
    }

    @Test
    void blankFromFallsBackToConfiguredUsername() throws Exception {
        MimeMessage mensaje = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mensaje);
        EmailSenderService s = service("smtp.example.test", "", "smtp-user@example.test");

        assertTrue(s.enviar(notificacion("daniela@ejemplo.cl")));

        jakarta.mail.internet.InternetAddress desde =
                (jakarta.mail.internet.InternetAddress) mensaje.getFrom()[0];
        assertEquals("smtp-user@example.test", desde.getAddress());
        assertEquals("Pastelería My Dreams", desde.getPersonal(),
                "Even the fallback address must show the brand display name");
    }

    @Test
    void sendFailureReturnsFalseAndNeverThrows() {
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(MimeMessage.class));
        EmailSenderService s = service("smtp.example.test", "no-reply@ejemplo.cl", "");

        assertDoesNotThrow(() -> assertFalse(s.enviar(notificacion("daniela@ejemplo.cl")),
                "A transport failure must be reported as false, never thrown"));
    }

    @Test
    void placeholderRecipientIsSkippedWithoutASendAttempt() {
        EmailSenderService s = service("smtp.example.test", "no-reply@ejemplo.cl", "");

        assertFalse(s.enviar(notificacion("sin email")));
        verifyNoInteractions(mailSender);
    }

    @Test
    void blankRecipientIsSkippedWithoutASendAttempt() {
        EmailSenderService s = service("smtp.example.test", "no-reply@ejemplo.cl", "");

        assertFalse(s.enviar(notificacion("  ")));
        verifyNoInteractions(mailSender);
    }

    @Test
    void missingSenderAddressReturnsFalseWithoutASendAttempt() {
        // Neither app.mail.from nor spring.mail.username: there is no valid
        // From header to build, so the attempt must fail fast, not throw
        EmailSenderService s = service("smtp.example.test", "", "");

        assertFalse(s.enviar(notificacion("daniela@ejemplo.cl")));
        verifyNoInteractions(mailSender);
    }
}
