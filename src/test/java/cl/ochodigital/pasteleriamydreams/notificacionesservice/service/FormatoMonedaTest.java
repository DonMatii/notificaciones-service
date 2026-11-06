package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The amount shown in the mails must match the amount shown in the web panel:
// Chilean pesos are grouped with a dot.
class FormatoMonedaTest {

    @Test
    void formateaConSeparadorDeMilesChileno() {
        assertEquals("$87.500", FormatoMoneda.clp(87500));
        assertEquals("$25.990", FormatoMoneda.clp(25990));
        assertEquals("$1.000", FormatoMoneda.clp(1000));
        assertEquals("$0", FormatoMoneda.clp(0));
    }

    @Test
    void sinMontoDevuelveElTextoPorDefecto() {
        assertEquals("sin dato", FormatoMoneda.clp(null));
    }
}
