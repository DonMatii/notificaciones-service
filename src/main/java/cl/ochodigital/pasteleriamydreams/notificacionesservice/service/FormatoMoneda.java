package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import java.util.Locale;

/**
 * Formats an order amount for the customer-facing mails.
 *
 * Chilean pesos are grouped with a dot ("$25.990"), exactly like the web
 * panel and the public order-status page render them (toLocaleString("es-CL")).
 * Concatenating the raw integer printed "$25990" in the mails while the site
 * showed "$25.990" for the same order.
 *
 * The locale is pinned on purpose: the output must not depend on the default
 * locale of the machine that runs the service.
 */
public final class FormatoMoneda {

    private static final Locale LOCALE_CL = Locale.of("es", "CL");

    private FormatoMoneda() {
    }

    /**
     * @param monto amount in pesos; {@code null} means "the event carried no amount"
     * @return the formatted amount, or the "sin dato" fallback when there is none
     */
    public static String clp(Integer monto) {
        if (monto == null) {
            return "sin dato";
        }
        return "$" + String.format(LOCALE_CL, "%,d", monto);
    }
}
