package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import jakarta.mail.Address;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// RF-09 branded email: one multipart/alternative message with the HTML part
// (inline logo via cid:) plus the text/plain fallback, and a From header that
// shows the brand instead of a bare address. The sender contract is unchanged:
// returns true only on a confirmed send and never throws.
class EmailSenderMarcaTest {

    private static final String CODIGO_SEGUIMIENTO = "9f8e7d6c5b4a39281706f5e4d3c2b1a0";
    private static final String SITIO_PRUEBA = "https://tienda.test";
    private static final String ASUNTO_MARCA = "Pastelería My Dreams — Recibimos tu pedido";

    private JavaMailSender mailSender;
    private ObjectProvider<JavaMailSender> mailSenderProvider;
    private MimeMessage mensaje;

    @BeforeEach
    void setUp() throws Exception {
        mailSender = mockSender();
        mailSenderProvider = provider();
        // mail.host: saveChanges() derives the Message-ID host from it; without
        // it JavaMail falls back to InetAddress.getLocalHost() (~9.5s DNS wait)
        Properties props = new Properties();
        props.setProperty("mail.host", "localhost");
        mensaje = new MimeMessage(jakarta.mail.Session.getInstance(props));
        org.mockito.Mockito.when(mailSender.createMimeMessage()).thenReturn(mensaje);
    }

    @Test
    void elMensajeEsMultipartAlternativeConTextoPlanoHtmlYLogoInline() throws Exception {
        assertTrue(enviar(notificacion()));

        Object contenido = mensaje.getContent();
        assertInstanceOf(Multipart.class, contenido,
                "The message must be multipart, was: " + tipoDe(contenido));

        List<Part> partes = new ArrayList<>();
        List<Multipart> multiparts = new ArrayList<>();
        recolectar(mensaje, partes, multiparts);

        // (a) a multipart/alternative carrying BOTH text/plain and text/html
        boolean hayAlternative = multiparts.stream().anyMatch(mp ->
                mp.getContentType() != null
                        && mp.getContentType().toLowerCase().contains("alternative")
                        && tieneTipo(mp, "text/plain")
                        && tieneTipo(mp, "text/html"));
        assertTrue(hayAlternative,
                "The message must contain a multipart/alternative with text/plain and text/html parts;"
                        + " structure:" + descripcion());

        // (b) the HTML references the logo by cid:
        assertTrue(html().contains("cid:logo"),
                "The HTML part must reference the inline logo by cid:");

        // (c) the logo travels inline with a Content-ID header
        assertTrue(partes.stream().anyMatch(this::tieneContentIdLogo),
                "The logo must travel as an inline part with Content-ID");
    }

    @Test
    void fromLlevaElNombreVisibleDeLaMarca() throws Exception {
        assertTrue(enviar(notificacion()));

        Address[] desde = mensaje.getFrom();
        assertNotNull(desde, "The message must have a From header");
        InternetAddress internet = assertInstanceOf(InternetAddress.class, desde[0]);
        assertEquals("Pastelería My Dreams", internet.getPersonal(),
                "The From header must show the brand as display name (A4)");
        assertEquals("no-reply@ejemplo.cl", internet.getAddress(),
                "The From address must stay the configured MAIL_FROM");
    }

    @Test
    void htmlTraeClienteResumenFirmaCodigoYEnlaceAlEstado() throws Exception {
        assertTrue(enviar(notificacion()));

        String html = html();
        assertTrue(html.contains("Daniela Soto"), "The HTML must greet the customer");
        assertTrue(html.contains("Torta de chocolate"), "The HTML must show the products");
        assertTrue(html.contains("$25990"), "The HTML must show the order total");
        assertTrue(html.contains("Pastelería My Dreams"), "The HTML must carry the brand signature");
        assertTrue(html.contains(CODIGO_SEGUIMIENTO),
                "The tracking code must appear as copyable text in the HTML");
        assertTrue(html.contains(SITIO_PRUEBA + "/estado"),
                "The HTML must link to the plain /estado route of the public site");

        // The text/plain fallback keeps the persisted body and adds the code too
        String texto = textoPlano();
        assertTrue(texto.contains("Hola Daniela"), "The plain part must keep the fallback body");
        assertTrue(texto.contains(CODIGO_SEGUIMIENTO),
                "The plain part must also show the tracking code");
    }

    @Test
    void sinCodigoDeSeguimientoSeOmiteElBloquePeroElRestoDelHtmlSeMantiene() throws Exception {
        Notificacion sinCodigo = notificacion();
        sinCodigo.setCodigoSeguimiento(null);

        assertTrue(enviar(sinCodigo));

        String html = html();
        assertFalse(html.contains("Código de seguimiento"),
                "Without a tracking code the tracking block must be omitted");
        assertFalse(html.contains("Consultar estado del pedido"),
                "Without a tracking code there must be no status button");
        assertFalse(html.contains(CODIGO_SEGUIMIENTO),
                "Without a tracking code no code may be rendered");
        assertTrue(html.contains("Daniela Soto"),
                "The rest of the branded HTML must survive a missing code");
        assertFalse(textoPlano().contains("Codigo de seguimiento"),
                "The plain part must not add a tracking line when the code is missing");
    }

    @Test
    void datosDelClienteYElResumenSeEscapanEnElHtml() throws Exception {
        Notificacion inyectada = notificacion();
        inyectada.setCliente("<script>alert(1)</script>");
        inyectada.setCuerpo("""
                Hola,

                Productos: <img src=x onerror=alert(2)>
                Cantidad total de articulos: 1
                Total: $1000""");

        assertTrue(enviar(inyectada));

        String html = html();
        assertFalse(html.contains("<script>"),
                "Customer data must never inject a script tag into the HTML");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"),
                "The greeting must render the escaped customer name");
        assertFalse(html.contains("<img src=x"),
                "Product data must never inject raw markup (the logo img is cid:)");
        assertTrue(html.contains("&lt;img src=x onerror=alert(2)&gt;"),
                "The summary must render the escaped product value");
    }

    @Test
    void cuerpoSinEtiquetasCaeAlBloquePreConElTextoCompleto() throws Exception {
        Notificacion sinEtiquetas = notificacion();
        sinEtiquetas.setCuerpo("""
                Hola Daniela,

                Pedido especial sin etiquetas estandar.""");

        assertTrue(enviar(sinEtiquetas));

        String html = html();
        assertTrue(html.contains("<pre"),
                "A body without the fixed labels must fall back to a pre block");
        assertTrue(html.contains("Pedido especial sin etiquetas estandar."),
                "The fallback must keep the whole plain body");
        assertFalse(html.contains("Productos</td>"),
                "No summary table rows may be rendered without labels");
    }

    // --- helpers -----------------------------------------------------------

    // Runs the sender and then materializes the MIME headers exactly like the
    // real transport does: JavaMailSenderImpl.doSend calls saveChanges() before
    // Transport.sendMessage, and the mocked send() skips that step. Without it
    // the nested parts keep no Content-Type header (setContent(Multipart) only
    // installs the DataHandler) and structural assertions see only defaults.
    private boolean enviar(Notificacion notificacion) throws Exception {
        boolean enviado = service().enviar(notificacion);
        mensaje.saveChanges();
        return enviado;
    }

    // Walks the MIME tree collecting every part and multipart container
    private void recolectar(Part parte, List<Part> partes, List<Multipart> multiparts) throws Exception {
        partes.add(parte);
        Object contenido = parte.getContent();
        if (contenido instanceof Multipart mp) {
            multiparts.add(mp);
            for (int i = 0; i < mp.getCount(); i++) {
                recolectar(mp.getBodyPart(i), partes, multiparts);
            }
        }
    }

    // True when the multipart has a body part of the given MIME type.
    // Never throws so it can be used inside stream predicates.
    private boolean tieneTipo(Multipart mp, String mimeType) {
        try {
            for (int i = 0; i < mp.getCount(); i++) {
                if (mp.getBodyPart(i).isMimeType(mimeType)) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    // True when the part carries a Content-ID referencing the inline logo.
    // Never throws so it can be used inside stream predicates.
    private boolean tieneContentIdLogo(Part parte) {
        try {
            String[] cid = parte.getHeader("Content-ID");
            return cid != null && cid.length > 0 && cid[0].contains("logo");
        } catch (Exception e) {
            return false;
        }
    }

    // Indented Content-Type dump of the whole MIME tree plus the message
    // headers, used in assertion messages so a structural regression is
    // readable at a glance
    private String descripcion() throws Exception {
        StringBuilder sb = new StringBuilder("\n  MSG-HEADERS:");
        java.util.Enumeration<jakarta.mail.Header> cabeceras = mensaje.getAllHeaders();
        while (cabeceras.hasMoreElements()) {
            jakarta.mail.Header cabecera = cabeceras.nextElement();
            sb.append(" [").append(cabecera.getName()).append('=').append(cabecera.getValue()).append(']');
        }
        sb.append(" content-class=").append(mensaje.getContent().getClass().getName());
        describir(mensaje, 1, sb);
        return sb.toString();
    }

    private void describir(Part parte, int nivel, StringBuilder sb) throws Exception {
        sb.append("\n").append("  ".repeat(nivel)).append(parte.getContentType());
        Object contenido = parte.getContent();
        if (contenido instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                describir(mp.getBodyPart(i), nivel + 1, sb);
            }
        } else if (contenido != null && !(contenido instanceof String)) {
            sb.append(" [").append(contenido.getClass().getSimpleName()).append("]");
        }
    }

    // First text/html part of the message
    private String html() throws Exception {
        return primerContenido("text/html");
    }

    // First text/plain part of the message
    private String textoPlano() throws Exception {
        return primerContenido("text/plain");
    }

    // Content of the first part matching the given MIME type (loop instead of
    // a stream: Part#isMimeType and Part#getContent throw a checked exception
    // that lambdas may not propagate)
    private String primerContenido(String mimeType) throws Exception {
        List<Part> partes = new ArrayList<>();
        recolectar(mensaje, partes, new ArrayList<>());
        for (Part parte : partes) {
            if (parte.isMimeType(mimeType)) {
                return parte.getContent().toString();
            }
        }
        throw new AssertionError("The message has no " + mimeType + " part");
    }

    private String tipoDe(Object contenido) {
        return contenido == null ? "null" : contenido.getClass().getName();
    }

    private org.springframework.mail.javamail.JavaMailSender mockSender() {
        return org.mockito.Mockito.mock(JavaMailSender.class);
    }

    private ObjectProvider<JavaMailSender> provider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> proveedor = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(proveedor.getObject()).thenReturn(mailSender);
        return proveedor;
    }

    private EmailSenderService service() {
        return new EmailSenderService(mailSenderProvider, "smtp.example.test",
                "no-reply@ejemplo.cl", "", "Pastelería My Dreams", SITIO_PRUEBA);
    }

    // Mirrors the row the listener persists: branded subject, plain-text body
    // with the fixed labels, opaque tracking code on the entity
    private Notificacion notificacion() {
        Notificacion n = new Notificacion();
        n.setPedidoId(501L);
        n.setCliente("Daniela Soto");
        n.setEmail("daniela@ejemplo.cl");
        n.setAsunto(ASUNTO_MARCA);
        n.setCuerpo("""
                Hola Daniela,

                Tu pedido 501 fue recibido correctamente.

                Productos: Torta de chocolate
                Cantidad total de articulos: 2
                Total: $25990

                Estado: pendiente de confirmacion. Enviaremos la confirmacion \
                por correo electronico cuando el pedido sea despachado.""");
        n.setCodigoSeguimiento(CODIGO_SEGUIMIENTO);
        return n;
    }
}
