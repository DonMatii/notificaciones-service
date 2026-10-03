package cl.ochodigital.pasteleriamydreams.notificacionesservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Seguridad de notificaciones: el listado contiene emails de clientes (PII),
// asi que exige header X-Api-Key (antes era publico)
@SpringBootTest
@AutoConfigureMockMvc
class NotificacionSeguridadIntegrationTest {

    // Misma key que define src/test/resources/application.properties
    private static final String API_KEY = "test-admin-key";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listadoSinApiKeyResponde401() throws Exception {
        mockMvc.perform(get("/api/notificaciones"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listadoConApiKeyIncorrectaResponde401() throws Exception {
        mockMvc.perform(get("/api/notificaciones")
                        .header("X-Api-Key", "otra-clave"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listadoConApiKeyValidaResponde200() throws Exception {
        mockMvc.perform(get("/api/notificaciones")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk());
    }
}
