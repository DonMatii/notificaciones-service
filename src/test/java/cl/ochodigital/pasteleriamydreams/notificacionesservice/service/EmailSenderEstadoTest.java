package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.NotificacionEstado;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Rendering of the STATUS email. The status mail lists the product prices and
// the order total side by side, so when a discount was applied the customer
// must be able to reconcile both numbers: the discount has to be visible.
// This is also the first coverage of this rendering path.
class EmailSenderEstadoTest {

    private JavaMailSender mailSender;
    private ObjectProvider<JavaMailSender> mailSenderProvider;
    private MimeMessage mensaje;

    @BeforeEach
    void setUp() throws Exception {
        mailSender = mock(JavaMailSender.class);
        mailSenderProvider = mock(ObjectProvider.class);
        when(mailSenderProvider.getObject()).thenReturn(mailSender);
        // mail.host: saveChanges() derives the Message-ID host from it; without
        // it JavaMail falls back to InetAddress.getLocalHost() (~9.5s DNS wait)
        Properties props = new Properties();
        props.setProperty("mail.host", "localhost");
        mensaje = new MimeMessage(jakarta.mail.Session.getInstance(props));
        when(mailSender.createMimeMessage()).thenReturn(mensaje);
    }

    @Test
    void elDescuentoSeMuestraEnElCorreoDeEstado() throws Exception {
        assertTrue(enviar(notificacionConDescuento()));

        String html = html();
        assertTrue(html.contains("Descuento aplicado"),
                "The status email must show the discount so the prices add up to the total");
        assertTrue(html.contains("-$2.100"),
                "The discount must be rendered with the Chilean thousands separator");
        assertTrue(html.contains("$18.900"),
                "The order total must still be rendered");
        assertTrue(html.contains("$11.000"),
                "The product prices must still be rendered");
    }

    @Test
    void sinDescuentoNoSeRenderizaLaFila() throws Exception {
        NotificacionEstado sinDescuento = notificacionConDescuento();
        sinDescuento.setCuerpo("""
                Hola Daniela,

                Estado actual: En preparación

                Total del pedido: $21.000
                """);

        assertTrue(enviar(sinDescuento));

        String html = html();
        assertFalse(html.contains("Descuento aplicado"),
                "Without a discount in the body there must be no discount row (old notifications)");
        assertTrue(html.contains("$21.000"),
                "The order total must still be rendered");
    }

    // --- helpers -----------------------------------------------------------

    private boolean enviar(NotificacionEstado notificacion) throws Exception {
        boolean enviado = service().enviar(notificacion);
        mensaje.saveChanges();
        return enviado;
    }

    private String html() throws Exception {
        List<Part> partes = new ArrayList<>();
        recolectar(mensaje, partes);
        for (Part parte : partes) {
            if (parte.isMimeType("text/html")) {
                return parte.getContent().toString();
            }
        }
        throw new AssertionError("The status email has no text/html part");
    }

    private void recolectar(Part parte, List<Part> partes) throws Exception {
        partes.add(parte);
        Object contenido = parte.getContent();
        if (contenido instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                recolectar(mp.getBodyPart(i), partes);
            }
        }
    }

    private EmailSenderService service() {
        return new EmailSenderService(mailSenderProvider, "smtp.example.test",
                "no-reply@ejemplo.cl", "", "Pastelería My Dreams", "https://tienda.test");
    }

    // Mirrors the row the listener persists for a discounted order: the product
    // prices add up to $21.000 but the order total is $18.900.
    private NotificacionEstado notificacionConDescuento() {
        NotificacionEstado n = new NotificacionEstado();
        n.setPedidoId(31L);
        n.setCliente("Daniela Soto");
        n.setEmail("daniela@ejemplo.cl");
        n.setEstadoPedido("EN_PREPARACION");
        n.setEstadoAnterior("RECIBIDO");
        n.setAsunto("Pastelería My Dreams - Tu pedido: En preparación");
        n.setCuerpo("""
                Hola Daniela,

                Tu pedido pasó de Recibido a En preparación.

                Estado actual: En preparación
                Tu pedido está en preparación.

                Descuento aplicado: -$2.100
                Total del pedido: $18.900
                """);
        n.setProductosJson("""
                [{"nombre":"Tarta de Yogurt","cantidad":1,"precioUnitario":11000,"subtotal":11000},\
                {"nombre":"Queque Vainilla","cantidad":2,"precioUnitario":5000,"subtotal":10000}]""");
        return n;
    }
}
