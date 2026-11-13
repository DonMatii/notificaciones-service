package cl.ochodigital.pasteleriamydreams.notificacionesservice.controller;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionRepository;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Endpoint mínimo de solo lectura para verificación/demo local del consumidor
@RestController
@RequestMapping("/api/notificaciones")
@CrossOrigin(origins = {"http://localhost:5173"})
public class NotificacionController {

    private final NotificacionRepository notificacionRepository;

    public NotificacionController(NotificacionRepository notificacionRepository) {
        this.notificacionRepository = notificacionRepository;
    }

    // Lista todas las notificaciones, las más recientes primero
    @GetMapping
    public List<Notificacion> listar() {
        return notificacionRepository.findAllByOrderByIdDesc();
    }
}
