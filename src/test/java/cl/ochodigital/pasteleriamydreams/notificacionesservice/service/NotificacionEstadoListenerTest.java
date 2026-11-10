package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.NotificacionEstado;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionEstadoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
            {"evento":"PedidoEstadoCambiado","id":301,"cliente":"Daniela Soto","email":"daniela@ejemplo.cl","estadoAnterior":"RECIBIDO","estadoNuevo":"EN_PREPARACION","total":25990,"fecha":"2026-10-01T12:30:00","codigoConsulta":"0a1b2c3d4e5f60718293a4b5c6d7e8f9","productos":[]}""";

    private static final String EVENTO_DESPACHADO = """
            {"evento":"PedidoEstadoCambiado","id":301,"cliente":"Daniela Soto","email":"daniela@ejemplo.cl","estadoAnterior":"EN_PREPARACION","estadoNuevo":"DESPACHADO","total":25990,"fecha":"2026-10-01T13:00:00","codigoConsulta":"0a1b2c3d4e5f60718293a4b5c6d7e8f9","productos":[]}""";

    // The products add up to $21.000 but the order total is $18.900: the
    // difference is the discount the customer was granted.
    private static final String EVENTO_CON_DESCUENTO = """
            {"evento":"PedidoEstadoCambiado","id":302,"cliente":"Daniela Soto","email":"daniela@ejemplo.cl","estadoAnterior":"RECIBIDO","estadoNuevo":"EN_PREPARACION","total":18900,"fecha":"2026-10-01T12:30:00","codigoConsulta":"0a1b2c3d4e5f60718293a4b5c6d7e8f9","productos":[{"nombre":"Tarta de Yogurt","cantidad":1,"precioUnitario":11000,"subtotal":11000},{"nombre":"Queque Vainilla","cantidad":2,"precioUnitario":5000,"subtotal":10000}]}""";

    // Same products, but the total already matches them: there is no discount.
    private static final String EVENTO_SIN_DESCUENTO = """
            {"evento":"PedidoEstadoCambiado","id":303,"cliente":"Daniela Soto","email":"daniela@ejemplo.cl","estadoAnterior":"RECIBIDO","estadoNuevo":"EN_PREPARACION","total":21000,"fecha":"2026-10-01T12:30:00","codigoConsulta":"0a1b2c3d4e5f60718293a4b5c6d7e8f9","productos":[{"nombre":"Tarta de Yogurt","cantidad":1,"precioUnitario":11000,"subtotal":11000},{"nombre":"Queque Vainilla","cantidad":2,"precioUnitario":5000,"subtotal":10000}]}""";

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

    // Same rule as the order-confirmation mail: the customer must read the
    // amount with the Chilean thousands separator ("$25.990").
    @Test
    void elTotalDelCorreoDeEstadoSeGuardaConSeparadorDeMiles() {
        when(emailSenderService.habilitado()).thenReturn(false);

        assertDoesNotThrow(() -> listener.onPedidoEstadoCambiado(EVENTO_PREPARACION));

        ArgumentCaptor<NotificacionEstado> captor = ArgumentCaptor.forClass(NotificacionEstado.class);
        verify(repository, times(1)).save(captor.capture());
        assertTrue(captor.getValue().getCuerpo().contains("Total del pedido: $25.990"),
                "The status mail total must use the Chilean thousands separator");
    }

    // The status mail lists the product prices AND the order total, so the
    // customer sees a total that does not add up unless the discount is shown.
    @Test
    void elDescuentoSeGuardaEnElCuerpoDelCorreoDeEstado() {
        when(emailSenderService.habilitado()).thenReturn(false);

        assertDoesNotThrow(() -> listener.onPedidoEstadoCambiado(EVENTO_CON_DESCUENTO));

        ArgumentCaptor<NotificacionEstado> captor = ArgumentCaptor.forClass(NotificacionEstado.class);
        verify(repository, times(1)).save(captor.capture());
        String cuerpo = captor.getValue().getCuerpo();
        assertTrue(cuerpo.contains("Descuento aplicado: -$2.100"),
                "The discount must be visible so the prices add up to the total");
        assertTrue(cuerpo.contains("Total del pedido: $18.900"),
                "The order total must stay in the body");
    }

    @Test
    void sinDescuentoNoSeAgregaLaLinea() {
        when(emailSenderService.habilitado()).thenReturn(false);

        assertDoesNotThrow(() -> listener.onPedidoEstadoCambiado(EVENTO_SIN_DESCUENTO));

        ArgumentCaptor<NotificacionEstado> captor = ArgumentCaptor.forClass(NotificacionEstado.class);
        verify(repository, times(1)).save(captor.capture());
        String cuerpo = captor.getValue().getCuerpo();
        assertFalse(cuerpo.contains("Descuento aplicado"),
                "When the total already matches the products there must be no discount line");
        assertTrue(cuerpo.contains("Total del pedido: $21.000"),
                "The order total must stay in the body");
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
    void aPoisonPillIsRethrownToReachTheDlq() {
        // Nueva política de errores: la píldora de veneno ya no se traga en
        // silencio; se relanza para que el error handler la aísle en la DLQ.
        // El consumidor sigue vivo para los mensajes siguientes.
        assertThrows(IllegalArgumentException.class, () -> listener.onPedidoEstadoCambiado("this is not json {{{"));
        assertThrows(IllegalArgumentException.class, () -> listener.onPedidoEstadoCambiado(
                "{\"evento\":\"PedidoEstadoCambiado\",\"estadoNuevo\":\"ENTREGADO\"}"));
        verify(repository, never()).save(any(NotificacionEstado.class));
    }
}
