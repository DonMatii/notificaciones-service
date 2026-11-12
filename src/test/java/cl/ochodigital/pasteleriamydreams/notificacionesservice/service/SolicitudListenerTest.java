package cl.ochodigital.pasteleriamydreams.notificacionesservice.service;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.NotificacionSolicitud;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionSolicitudRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Verifica el correo de la solicitud de pedido de la Versión 1 (S2) dentro del
// listener. Mismo patrón de mocks y aserciones que NotificacionListenerEmailTest:
// el listener nunca revienta, el duplicado por solicitudId se salta sin enviar,
// y el estado de la fila solo se mueve cuando SMTP está configurado.
class SolicitudListenerTest {

    // Contrato JSON publicado por pedidos-service (record SolicitudCreadoEvent)
    private static final String EVENTO_SOLICITUD_401 = """
            {"evento":"SolicitudCreado","id":401,"nombre":"Daniela Soto","email":"daniela@ejemplo.cl","telefono":"+56 9 8765 4321","detalle":"Torta de chocolate x1, Cupcakes vainilla x6","mensaje":"Para el sabado por la tarde","fecha":"2026-11-12T12:30:00"}""";

    private static final String EVENTO_SOLICITUD_402_SIN_MENSAJE = """
            {"evento":"SolicitudCreado","id":402,"nombre":"Catherine Godoy","email":"cat@ejemplo.cl","telefono":null,"detalle":"Selva Negra x2","mensaje":null,"fecha":"2026-11-12T12:30:00"}""";

    private NotificacionSolicitudRepository repository;
    private EmailSenderService emailSenderService;
    private SolicitudListener listener;

    @BeforeEach
    void setUp() {
        repository = mock(NotificacionSolicitudRepository.class);
        emailSenderService = mock(EmailSenderService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        listener = new SolicitudListener(repository, objectMapper, emailSenderService);
    }

    // Un solo correo al cliente, con el asunto exacto del contrato y un cuerpo
    // que saluda por nombre, trae el detalle, el mensaje y un cierre cercano
    @Test
    void enviaUnCorreoAlClienteConElAsuntoYCuerpoDeLaSolicitud() {
        when(emailSenderService.habilitado()).thenReturn(true);
        when(emailSenderService.enviarSolicitud(any(Notificacion.class))).thenReturn(true);

        assertDoesNotThrow(() -> listener.onSolicitudCreada(EVENTO_SOLICITUD_401));

        ArgumentCaptor<Notificacion> captor = ArgumentCaptor.forClass(Notificacion.class);
        verify(emailSenderService, times(1)).enviarSolicitud(captor.capture());
        Notificacion correo = captor.getValue();
        assertEquals("Pastelería My Dreams — Recibimos tu solicitud", correo.getAsunto());
        assertEquals("daniela@ejemplo.cl", correo.getEmail());
        assertTrue(correo.getCuerpo().contains("Hola Daniela Soto"),
                "El correo debe saludar por nombre: " + correo.getCuerpo());
        assertTrue(correo.getCuerpo().contains("Torta de chocolate x1, Cupcakes vainilla x6"),
                "El correo debe traer el detalle de la solicitud: " + correo.getCuerpo());
        assertTrue(correo.getCuerpo().contains("Para el sabado por la tarde"),
                "El correo debe traer el mensaje del cliente: " + correo.getCuerpo());
        assertTrue(correo.getCuerpo().contains("Te contactaremos pronto"),
                "El correo debe tener un cierre cercano: " + correo.getCuerpo());
    }

    // Sin mensaje el correo se manda igual: sin la sección del mensaje y con el cierre
    @Test
    void sinMensajeElCorreoVaSinEsaSeccionPeroConElCierre() {
        when(emailSenderService.habilitado()).thenReturn(true);
        when(emailSenderService.enviarSolicitud(any(Notificacion.class))).thenReturn(true);

        assertDoesNotThrow(() -> listener.onSolicitudCreada(EVENTO_SOLICITUD_402_SIN_MENSAJE));

        ArgumentCaptor<Notificacion> captor = ArgumentCaptor.forClass(Notificacion.class);
        verify(emailSenderService, times(1)).enviarSolicitud(captor.capture());
        String cuerpo = captor.getValue().getCuerpo();
        assertTrue(cuerpo.contains("Hola Catherine Godoy"));
        assertTrue(cuerpo.contains("Selva Negra x2"));
        assertFalse(cuerpo.contains("Tu mensaje"),
                "Sin mensaje no debe aparecer su sección: " + cuerpo);
        assertTrue(cuerpo.contains("Te contactaremos pronto"));
    }

    // Control de duplicados: una solicitud produce EXACTAMENTE un correo, aunque
    // Kafka reentregue el evento (at-least-once)
    @Test
    void laSolicitudDuplicadaSeSaltaSinNingunIntentoDeEnvio() {
        when(repository.existsBySolicitudId(401L)).thenReturn(true);

        assertDoesNotThrow(() -> listener.onSolicitudCreada(EVENTO_SOLICITUD_401));

        verify(repository, never()).save(any(NotificacionSolicitud.class));
        verify(emailSenderService, never()).habilitado();
        verify(emailSenderService, never()).enviarSolicitud(any(Notificacion.class));
    }

    @Test
    void sinSmtpLaNotificacionQuedaPendienteYNoSeEnviaNada() {
        when(emailSenderService.habilitado()).thenReturn(false);

        assertDoesNotThrow(() -> listener.onSolicitudCreada(EVENTO_SOLICITUD_401));

        ArgumentCaptor<NotificacionSolicitud> captor = ArgumentCaptor.forClass(NotificacionSolicitud.class);
        verify(repository, times(1)).save(captor.capture());
        assertEquals("PENDIENTE", captor.getValue().getEstado(),
                "Sin SMTP la fila debe quedar PENDIENTE, como en los otros listeners");
        verify(emailSenderService, never()).enviarSolicitud(any(Notificacion.class));
    }

    @Test
    void unEnvioFallidoMarcaErrorYElListenerNoRevienta() {
        when(emailSenderService.habilitado()).thenReturn(true);
        when(emailSenderService.enviarSolicitud(any(Notificacion.class))).thenReturn(false);

        assertDoesNotThrow(() -> listener.onSolicitudCreada(EVENTO_SOLICITUD_401),
                "Un envío fallido nunca debe escapar del listener como excepción");

        ArgumentCaptor<NotificacionSolicitud> captor = ArgumentCaptor.forClass(NotificacionSolicitud.class);
        verify(repository, times(2)).save(captor.capture());
        assertEquals("ERROR", captor.getValue().getEstado());
        verify(emailSenderService, times(1)).enviarSolicitud(any(Notificacion.class));
    }
}
