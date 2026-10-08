package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionRepository;
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

// Verifies the RF-09 email hook inside the listener. The listener contract is
// unchanged: it never throws, the idempotency guard still short-circuits
// duplicates, and the notification state only moves when SMTP is configured.
class NotificacionListenerEmailTest {

    // Exact JSON contract published by pedidos-service (record PedidoCreadoEvent)
    private static final String EVENTO_PEDIDO_301 = """
            {"evento":"PedidoCreado","id":301,"cliente":"Daniela Soto","email":"daniela@ejemplo.cl","producto":"Torta de chocolate","cantidad":2,"total":25990,"fecha":"2026-10-01T12:30:00"}""";

    private NotificacionRepository repository;
    private EmailSenderService emailSenderService;
    private NotificacionListener listener;

    @BeforeEach
    void setUp() {
        repository = mock(NotificacionRepository.class);
        emailSenderService = mock(EmailSenderService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        listener = new NotificacionListener(repository, objectMapper, emailSenderService);
    }

    @Test
    void notificationStaysPendingWhenSmtpIsNotConfigured() {
        when(emailSenderService.habilitado()).thenReturn(false);

        assertDoesNotThrow(() -> listener.onPedidoCreado(EVENTO_PEDIDO_301));

        ArgumentCaptor<Notificacion> captor = ArgumentCaptor.forClass(Notificacion.class);
        verify(repository, times(1)).save(captor.capture());
        assertEquals("PENDIENTE", captor.getValue().getEstado(),
                "Without SMTP the row must stay PENDIENTE, exactly as before RF-09");
        verify(emailSenderService, never()).enviar(any(Notificacion.class));
    }

    @Test
    void notificationIsMarkedEnviadoWhenTheSendSucceeds() {
        when(emailSenderService.habilitado()).thenReturn(true);
        when(emailSenderService.enviar(any(Notificacion.class))).thenReturn(true);

        assertDoesNotThrow(() -> listener.onPedidoCreado(EVENTO_PEDIDO_301));

        ArgumentCaptor<Notificacion> captor = ArgumentCaptor.forClass(Notificacion.class);
        verify(repository, times(2)).save(captor.capture());
        assertEquals("ENVIADO", captor.getValue().getEstado());
        assertEquals(301L, captor.getValue().getPedidoId());
        verify(emailSenderService, times(1)).enviar(any(Notificacion.class));
    }

    @Test
    void notificationIsMarkedErrorWhenTheSendFailsAndTheListenerDoesNotThrow() {
        when(emailSenderService.habilitado()).thenReturn(true);
        when(emailSenderService.enviar(any(Notificacion.class))).thenReturn(false);

        assertDoesNotThrow(() -> listener.onPedidoCreado(EVENTO_PEDIDO_301),
                "A failed send must never escape the listener as an exception");

        ArgumentCaptor<Notificacion> captor = ArgumentCaptor.forClass(Notificacion.class);
        verify(repository, times(2)).save(captor.capture());
        assertEquals("ERROR", captor.getValue().getEstado());
        verify(emailSenderService, times(1)).enviar(any(Notificacion.class));
    }

    @Test
    void duplicatePedidoIdIsStillSkippedWithoutAnySendAttempt() {
        when(repository.existsByPedidoId(301L)).thenReturn(true);

        assertDoesNotThrow(() -> listener.onPedidoCreado(EVENTO_PEDIDO_301));

        verify(repository, never()).save(any(Notificacion.class));
        verify(emailSenderService, never()).habilitado();
        verify(emailSenderService, never()).enviar(any(Notificacion.class));
    }
}
