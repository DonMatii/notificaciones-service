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

// RF-09/RF-11: el correo de confirmación debe llevar la marca, nunca el id
// secuencial interno, y el código de seguimiento opaco del evento debe quedar
// en la fila. El contrato del listener no cambia: nunca revienta, el control de
// idempotencia sigue aplicando y sin SMTP la fila queda PENDIENTE.
class NotificacionListenerMarcaTest {

    // Payload exacto que publica pedidos-service después de RF-09/RF-11: los
    // mismos campos de antes más codigoConsulta al final
    private static final String EVENTO_CON_CODIGO = """
            {"evento":"PedidoCreado","id":777,"cliente":"Daniela Soto","email":"daniela@ejemplo.cl","producto":"Torta de chocolate","cantidad":2,"total":25990,"fecha":"2026-10-01T12:30:00","codigoConsulta":"9f8e7d6c5b4a39281706f5e4d3c2b1a0"}""";

    // Eventos antiguos o parciales sin el código de seguimiento no deben romper nada
    private static final String EVENTO_SIN_CODIGO = """
            {"evento":"PedidoCreado","id":778,"cliente":"Raul Pino","email":"raul@ejemplo.cl","producto":"Kuchen de manzana","cantidad":1,"total":9980,"fecha":"2026-10-01T12:35:00"}""";

    private NotificacionRepository repository;
    private EmailSenderService emailSenderService;
    private NotificacionListener listener;

    @BeforeEach
    void setUp() {
        repository = mock(NotificacionRepository.class);
        emailSenderService = mock(EmailSenderService.class);
        // Misma tolerancia que el ObjectMapper autoconfigurado de Spring Boot que
        // se usa en producción (FAIL_ON_UNKNOWN_PROPERTIES=false): el record
        // espejo no modela codigoConsulta, así que el mapper estricto a secas lo rechazaría
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

    // Captura la única fila guardada cuando SMTP no está configurado
    private Notificacion guardarUnaVez() {
        ArgumentCaptor<Notificacion> captor = ArgumentCaptor.forClass(Notificacion.class);
        verify(repository, times(1)).save(captor.capture());
        return captor.getValue();
    }
}
