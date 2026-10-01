package cl.ochodigital.pasteleriamydreams.notificacionesservice.controller;

import cl.ochodigital.pasteleriamydreams.notificacionesservice.model.Notificacion;
import cl.ochodigital.pasteleriamydreams.notificacionesservice.repository.NotificacionRepository;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Minimal read-only endpoint for local verification/demo of the consumer
@RestController
@RequestMapping("/api/notificaciones")
@CrossOrigin(origins = {"http://localhost:5173"})
public class NotificacionController {

    private final NotificacionRepository notificacionRepository;

    public NotificacionController(NotificacionRepository notificacionRepository) {
        this.notificacionRepository = notificacionRepository;
    }

    // Lists all notifications, newest first
    @GetMapping
    public List<Notificacion> listar() {
        return notificacionRepository.findAllByOrderByIdDesc();
    }
}
