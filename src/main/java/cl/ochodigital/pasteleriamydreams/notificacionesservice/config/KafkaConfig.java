package cl.ochodigital.pasteleriamydreams.notificacionesservice.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

// Política de errores de los consumidores de la cola (rúbrica: éxito / falla
// recuperable / falla definitiva):
//  - Las fallas TRANSITORIAS (la base o la infraestructura caídas) se reintentan
//    con un backoff fijo (app.kafka.reintentos.*) y solo tras agotar los
//    reintentos el mensaje pasa a la DLQ.
//  - Las fallas DEFINITIVAS (payload ilegible, evento inválido) omiten los
//    reintentos y van directo a la DLQ.
//  - Cada mensaje que llega a la DLQ conserva el payload original más los
//    headers con la excepción, para poder revisarlo y reproducirlo a mano. Ya
//    no se descarta nada en silencio.
// Los duplicados nunca llegan acá: el control de idempotencia los salta antes
// de que todo esto se ejecute (control de duplicados).
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    // Reintentos después del primer intento (2 = hasta 3 intentos en total)
    @Value("${app.kafka.reintentos.max:2}")
    private int maxReintentos;

    // Espera entre reintentos en milisegundos (configurable por entorno)
    @Value("${app.kafka.reintentos.espera-ms:1000}")
    private long esperaEntreReintentosMs;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> kafkaTemplate) {

        // Los mensajes fallidos terminan en <topic>.DLT (p. ej. pedidos.DLT) con
        // el motivo de la falla en los headers; el aislamiento mismo se registra como error
        DeadLetterPublishingRecoverer publicadorDlt = new DeadLetterPublishingRecoverer(kafkaTemplate) {
            @Override
            public void accept(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> registro, Exception error) {
                log.error("Message isolated to the DLQ: topic={} partition={} offset={} - Reason: {}",
                        registro.topic(), registro.partition(), registro.offset(), error.getMessage());
                super.accept(registro, error);
            }
        };

        DefaultErrorHandler manejador = new DefaultErrorHandler(publicadorDlt,
                new FixedBackOff(esperaEntreReintentosMs, maxReintentos));

        // Fallas definitivas: sin reintentos, directo a la DLQ
        manejador.addNotRetryableExceptions(
                JsonProcessingException.class,
                IllegalArgumentException.class,
                DeserializationException.class);

        // Trazabilidad: cada intento y la entrega final a la DLQ quedan registrados
        manejador.setRetryListeners((registro, error, intento) ->
                log.warn("Attempt #{} failed for message on {} (key {}): {}",
                        intento, registro.topic(), registro.key(), error.getMessage()));

        ConcurrentKafkaListenerContainerFactory<String, String> fabrica =
                new ConcurrentKafkaListenerContainerFactory<>();
        fabrica.setConsumerFactory(consumerFactory);
        fabrica.setCommonErrorHandler(manejador);
        return fabrica;
    }
}
