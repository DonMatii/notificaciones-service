package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import java.util.Locale;

/**
 * Formatea un monto de pedido para los correos que ve el cliente.
 *
 * Los pesos chilenos se agrupan con punto ("$25.990"), exactamente igual que en
 * el panel web y en la página pública de estado del pedido (toLocaleString("es-CL")).
 * Concatenar el entero crudo imprimía "$25990" en los correos mientras el sitio
 * mostraba "$25.990" para el mismo pedido.
 *
 * El locale está fijado a propósito: la salida no debe depender del locale por
 * defecto de la máquina que corre el servicio.
 */
public final class FormatoMoneda {

    private static final Locale LOCALE_CL = Locale.of("es", "CL");

    private FormatoMoneda() {
    }

    /**
     * @param monto monto en pesos; {@code null} significa "el evento no traía monto"
     * @return el monto formateado, o el respaldo "sin dato" cuando no hay ninguno
     */
    public static String clp(Integer monto) {
        if (monto == null) {
            return "sin dato";
        }
        return "$" + String.format(LOCALE_CL, "%,d", monto);
    }
}
