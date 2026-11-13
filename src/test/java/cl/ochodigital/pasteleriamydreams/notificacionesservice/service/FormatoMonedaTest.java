package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// El monto que se muestra en los correos debe coincidir con el que muestra el
// panel web: los pesos chilenos se agrupan con punto.
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
