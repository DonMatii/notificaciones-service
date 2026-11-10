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

// Error policy for the queue consumers (rubric: success / recoverable failure /
// definitive failure):
//  - TRANSIENT failures (DB or infra down) are retried with a fixed backoff
//    (app.kafka.reintentos.*) and only after exhausting retries the message is
//    moved to the DLQ.
//  - DEFINITIVE failures (unreadable payload, invalid event) skip retries and
//    go straight to the DLQ.
//  - Every message that reaches the DLQ keeps the original payload plus the
//    exception headers, so it can be reviewed and replayed by hand. Nothing is
//    silently dropped anymore.
// Duplicates never get here: the idempotency guard skips them before any of
// this runs (control de duplicados).
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    // Retries after the first attempt (2 = up to 3 tries total)
    @Value("${app.kafka.reintentos.max:2}")
    private int maxReintentos;

    // Wait between retries in milliseconds (configurable per environment)
    @Value("${app.kafka.reintentos.espera-ms:1000}")
    private long esperaEntreReintentosMs;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> kafkaTemplate) {

        // Failed messages land in <topic>.DLT (e.g. pedidos.DLT) with the
        // failure reason in the headers; the isolation itself is logged as error
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

        // Definitive failures: no retries, straight to the DLQ
        manejador.addNotRetryableExceptions(
                JsonProcessingException.class,
                IllegalArgumentException.class,
                DeserializationException.class);

        // Traceability: every attempt and the final DLQ delivery are logged
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
