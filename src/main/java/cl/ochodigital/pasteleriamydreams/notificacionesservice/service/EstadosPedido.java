package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import java.util.Map;

// Fuente única de la verdad sobre cómo se REDACTA cada estado del pedido al
// cliente. Tanto el listener (asunto y cuerpo en texto plano) como
// EmailSenderService (badge del HTML) leen de acá, así que la redacción nunca
// puede divergir entre las dos partes de un mismo correo.
//
// Los códigos son los que valida pedidos-service en PedidoService.ESTADOS_VALIDOS.
// RECIBIDO falta a propósito: ese estado ya produjo la confirmación "Recibimos tu
// pedido" cuando se creó el pedido, así que nunca vuelve a mandar correo (S4).
public final class EstadosPedido {

    // Etiqueta legible que se muestra en el badge del correo y en el panel de administración
    private static final Map<String, String> ETIQUETAS = Map.of(
            "EN_PREPARACION", "En preparación",
            "DESPACHADO", "Despachado",
            "ENTREGADO", "Entregado");

    // Una frase por estado: lo que este paso realmente significa para el cliente.
    // Concreto, sin vaguedades tipo "en unos días": cada mensaje dice qué sigue.
    private static final Map<String, String> MENSAJES = Map.of(
            "EN_PREPARACION", "Tu pedido está en preparación. Lo estamos elaborando con mucho cariño y te avisamos en cuanto esté listo para despacho.",
            "DESPACHADO", "Tu pedido ya salió de la pastelería y va en camino a tu domicilio. Te avisamos cuando sea entregado.",
            "ENTREGADO", "Tu pedido fue entregado. ¡Gracias por confiar en Pastelería "
                    + "My Dreams, que lo disfrutes!");

    private EstadosPedido() {
    }

    // Estados que mandan correo, en el orden en que ocurren en la vida real de un pedido
    public static final String[] CON_CORREO = {"EN_PREPARACION", "DESPACHADO", "ENTREGADO"};

    // True cuando este estado se anuncia por correo (S4: todo cambio salvo el
    // RECIBIDO inicial, que ya cubrió el correo de confirmación del pedido)
    public static boolean enviaCorreo(String codigo) {
        return codigo != null && ETIQUETAS.containsKey(codigo);
    }

    // Etiqueta para el cliente, o el código tal cual cuando el estado no es conocido
    public static String etiqueta(String codigo) {
        return ETIQUETAS.getOrDefault(codigo, codigo == null ? "" : codigo);
    }

    // Frase que se muestra bajo el badge; vacía cuando el estado es desconocido
    public static String mensaje(String codigo) {
        return MENSAJES.getOrDefault(codigo, "");
    }
}
