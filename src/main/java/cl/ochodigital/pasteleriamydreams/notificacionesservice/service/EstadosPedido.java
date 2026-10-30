package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import java.util.Map;

// Single source of truth for how each order state is WORDED to the customer.
// Both the listener (subject and plain body) and EmailSenderService (HTML badge)
// read from here, so the wording can never drift between the two parts of the
// same email.
//
// Codes are the ones pedidos-service validates in PedidoService.ESTADOS_VALIDOS.
// RECIBIDO is absent on purpose: that state already produced the "Recibimos tu
// pedido" confirmation when the order was created, so it never emails again (S4).
public final class EstadosPedido {

    // Human label shown in the email badge and in the admin panel
    private static final Map<String, String> ETIQUETAS = Map.of(
            "EN_PREPARACION", "En preparación",
            "DESPACHADO", "Despachado",
            "ENTREGADO", "Entregado");

    // One sentence per state: what this step actually means for the customer
    private static final Map<String, String> MENSAJES = Map.of(
            "EN_PREPARACION", "Tu pedido ya está en preparación en la pastelería. "
                    + "En cuanto esté listo lo despachamos y te avisamos por correo.",
            "DESPACHADO", "Tu pedido salió de la pastelería y va en camino hacia ti. "
                    + "En unos días te lo entregamos.",
            "ENTREGADO", "Tu pedido fue entregado. ¡Gracias por confiar en Pastelería "
                    + "My Dreams! Si algo no estuvo bien, respóndenos este correo.");

    private EstadosPedido() {
    }

    // States that send an email, in the order they happen in a real order's life
    public static final String[] CON_CORREO = {"EN_PREPARACION", "DESPACHADO", "ENTREGADO"};

    // True when this state announces itself by email (S4: every change but the
    // initial RECIBIDO, which the order-confirmation email already covered)
    public static boolean enviaCorreo(String codigo) {
        return codigo != null && ETIQUETAS.containsKey(codigo);
    }

    // Customer-facing label, or the raw code when it is not a known state
    public static String etiqueta(String codigo) {
        return ETIQUETAS.getOrDefault(codigo, codigo == null ? "" : codigo);
    }

    // Sentence shown under the badge; empty when the state is unknown
    public static String mensaje(String codigo) {
        return MENSAJES.getOrDefault(codigo, "");
    }
}
