package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// RF-09/RF-11: the confirmation email must carry the brand, never the internal
// sequential id, and the opaque tracking code from the event must land on the
// row. The listener contract itself is unchanged: it never throws, the
// idempotency guard still applies and without SMTP the row stays PENDIENTE.
class NotificacionListenerMarcaTest {

    // Exact payload pedidos-service publishes after RF-09/RF-11: same fields as
    // before plus codigoConsulta at the end
    private static final String EVENTO_CON_CODIGO = """
            {"evento":"PedidoCreado","id":777,"cliente":"Daniela Soto","email":"daniela@ejemplo.cl","producto":"Torta de chocolate","cantidad":2,"total":25990,"fecha":"2026-10-01T12:30:00","codigoConsulta":"9f8e7d6c5b4a39281706f5e4d3c2b1a0"}""";

    // Older or partial events without the tracking code must not break anything
    private static final String EVENTO_SIN_CODIGO = """
            {"evento":"PedidoCreado","id":778,"cliente":"Raul Pino","email":"raul@ejemplo.cl","producto":"Kuchen de manzana","cantidad":1,"total":9980,"fecha":"2026-10-01T12:35:00"}""";

    private NotificacionRepository repository;
    private EmailSenderService emailSenderService;
    private NotificacionListener listener;

    @BeforeEach
    void setUp() {
        repository = mock(NotificacionRepository.class);
        emailSenderService = mock(EmailSenderService.class);
        // Same leniency as the Spring Boot auto-configured ObjectMapper used in
        // production (FAIL_ON_UNKNOWN_PROPERTIES=false): the mirror record does
        // not model codigoConsulta, so the vanilla strict mapper would reject it
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        listener = new NotificacionListener(repository, objectMapper, emailSenderService);
    }

    @Test
    void asuntoLlevaLaMarcaYNuncaElIdInterno() {
        when(emailSenderService.habilitado()).thenReturn(false);

        assertDoesNotThrow(() -> listener.onPedidoCreado(EVENTO_CON_CODIGO));

        Notificacion guardada = guardarUnaVez();
        String asunto = guardada.getAsunto();
        assertEquals("Pastelería My Dreams — Recibimos tu pedido", asunto,
                "The subject must be branded (A3)");
        assertFalse(asunto.contains("777"),
                "The subject must never expose the internal sequential id: " + asunto);
    }

    @Test
    void persisteElCodigoDeSeguimientoQueTraeElEvento() {
        when(emailSenderService.habilitado()).thenReturn(false);

        assertDoesNotThrow(() -> listener.onPedidoCreado(EVENTO_CON_CODIGO));

        Notificacion guardada = guardarUnaVez();
        assertEquals("9f8e7d6c5b4a39281706f5e4d3c2b1a0", guardada.getCodigoSeguimiento(),
                "The opaque tracking code must be stored so the email can be rebuilt");
        assertEquals("PENDIENTE", guardada.getEstado());
    }

    @Test
    void eventoSinCodigoQuedaEnNullSinRomperElContratoDelListener() {
        when(emailSenderService.habilitado()).thenReturn(false);

        assertDoesNotThrow(() -> listener.onPedidoCreado(EVENTO_SIN_CODIGO));

        Notificacion guardada = guardarUnaVez();
        assertNull(guardada.getCodigoSeguimiento(),
                "A missing tracking code must degrade to null, never fail");
        assertTrue(guardada.getAsunto().contains("Pastelería My Dreams"),
                "The subject must stay branded even without a tracking code");
        assertEquals("PENDIENTE", guardada.getEstado());
    }

    // Captures the single row saved when SMTP is not configured
    private Notificacion guardarUnaVez() {
        ArgumentCaptor<Notificacion> captor = ArgumentCaptor.forClass(Notificacion.class);
        verify(repository, times(1)).save(captor.capture());
        return captor.getValue();
    }
}
