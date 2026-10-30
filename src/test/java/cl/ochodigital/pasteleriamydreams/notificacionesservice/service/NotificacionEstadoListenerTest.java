package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.NotificacionEstado;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionEstadoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Verifies S3/S4: the status-change consumer stores one row per (pedido, estado)
// and hands it to the SMTP sender. The idempotency key is the PAIR, not pedidoId
// alone: one order walks up to three states and must produce three emails.
class NotificacionEstadoListenerTest {

    // Exact JSON contract published by pedidos-service (record PedidoEstadoCambiadoEvent)
    private static final String EVENTO_PREPARACION = """
            {"evento":"PedidoEstadoCambiado","id":301,"cliente":"Daniela Soto","email":"daniela@ejemplo.cl","estadoAnterior":"RECIBIDO","estadoNuevo":"EN_PREPARACION","total":25990,"fecha":"2026-10-01T12:30:00","codigoConsulta":"0a1b2c3d4e5f60718293a4b5c6d7e8f9"}""";

    private static final String EVENTO_DESPACHADO = """
            {"evento":"PedidoEstadoCambiado","id":301,"cliente":"Daniela Soto","email":"daniela@ejemplo.cl","estadoAnterior":"EN_PREPARACION","estadoNuevo":"DESPACHADO","total":25990,"fecha":"2026-10-01T13:00:00","codigoConsulta":"0a1b2c3d4e5f60718293a4b5c6d7e8f9"}""";

    private NotificacionEstadoRepository repository;
    private EmailSenderService emailSenderService;
    private NotificacionEstadoListener listener;

    @BeforeEach
    void setUp() {
        repository = mock(NotificacionEstadoRepository.class);
        emailSenderService = mock(EmailSenderService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        listener = new NotificacionEstadoListener(repository, objectMapper, emailSenderService);
    }

    @Test
    void persistsOneRowPerPedidoAndEstado() {
        when(emailSenderService.habilitado()).thenReturn(false);

        assertDoesNotThrow(() -> listener.onPedidoEstadoCambiado(EVENTO_PREPARACION));

        ArgumentCaptor<NotificacionEstado> captor = ArgumentCaptor.forClass(NotificacionEstado.class);
        verify(repository, times(1)).save(captor.capture());
        NotificacionEstado guardada = captor.getValue();
        assertEquals(301L, guardada.getPedidoId());
        assertEquals("EN_PREPARACION", guardada.getEstadoPedido(),
                "The row must store the state the customer is moving TO");
        assertEquals("RECIBIDO", guardada.getEstadoAnterior());
        assertEquals("PENDIENTE", guardada.getEstadoEnvio());
        assertEquals("0a1b2c3d4e5f60718293a4b5c6d7e8f9", guardada.getCodigoSeguimiento());
    }

    @Test
    void redeliveryOfTheSameStateIsSkipped() {
        when(repository.existsByPedidoIdAndEstadoPedido(301L, "EN_PREPARACION")).thenReturn(true);

        assertDoesNotThrow(() -> listener.onPedidoEstadoCambiado(EVENTO_PREPARACION));

        verify(repository, never()).save(any(NotificacionEstado.class));
        verify(emailSenderService, never()).enviar(any(NotificacionEstado.class));
    }

    @Test
    void theSameOrderInANewStateIsANewRowNotADuplicate() {
        // The pair guard must NOT block a later state of the same order
        when(repository.existsByPedidoIdAndEstadoPedido(301L, "EN_PREPARACION")).thenReturn(false);
        when(repository.existsByPedidoIdAndEstadoPedido(301L, "DESPACHADO")).thenReturn(false);
        when(emailSenderService.habilitado()).thenReturn(false);

        assertDoesNotThrow(() -> listener.onPedidoEstadoCambiado(EVENTO_PREPARACION));
        assertDoesNotThrow(() -> listener.onPedidoEstadoCambiado(EVENTO_DESPACHADO));

        ArgumentCaptor<NotificacionEstado> captor = ArgumentCaptor.forClass(NotificacionEstado.class);
        verify(repository, times(2)).save(captor.capture());
        assertEquals("EN_PREPARACION", captor.getAllValues().get(0).getEstadoPedido());
        assertEquals("DESPACHADO", captor.getAllValues().get(1).getEstadoPedido());
    }

    @Test
    void rowStaysPendingWhenSmtpIsNotConfigured() {
        when(emailSenderService.habilitado()).thenReturn(false);

        listener.onPedidoEstadoCambiado(EVENTO_PREPARACION);

        verify(emailSenderService, never()).enviar(any(NotificacionEstado.class));
    }

    @Test
    void rowIsMarkedEnviadoWhenTheSendSucceeds() {
        when(emailSenderService.habilitado()).thenReturn(true);
        when(emailSenderService.enviar(any(NotificacionEstado.class))).thenReturn(true);

        listener.onPedidoEstadoCambiado(EVENTO_PREPARACION);

        ArgumentCaptor<NotificacionEstado> captor = ArgumentCaptor.forClass(NotificacionEstado.class);
        verify(repository, times(2)).save(captor.capture());
        assertEquals("ENVIADO", captor.getValue().getEstadoEnvio());
    }

    @Test
    void rowIsMarkedErrorWhenTheSendFailsAndTheListenerDoesNotThrow() {
        when(emailSenderService.habilitado()).thenReturn(true);
        when(emailSenderService.enviar(any(NotificacionEstado.class))).thenReturn(false);

        assertDoesNotThrow(() -> listener.onPedidoEstadoCambiado(EVENTO_PREPARACION));

        ArgumentCaptor<NotificacionEstado> captor = ArgumentCaptor.forClass(NotificacionEstado.class);
        verify(repository, times(2)).save(captor.capture());
        assertEquals("ERROR", captor.getValue().getEstadoEnvio());
    }

    @Test
    void aPoisonPillNeverKillsTheConsumer() {
        assertDoesNotThrow(() -> listener.onPedidoEstadoCambiado("this is not json {{{"));
        assertDoesNotThrow(() -> listener.onPedidoEstadoCambiado(
                "{\"evento\":\"PedidoEstadoCambiado\",\"estadoNuevo\":\"ENTREGADO\"}"));
        verify(repository, never()).save(any(NotificacionEstado.class));
    }
}
