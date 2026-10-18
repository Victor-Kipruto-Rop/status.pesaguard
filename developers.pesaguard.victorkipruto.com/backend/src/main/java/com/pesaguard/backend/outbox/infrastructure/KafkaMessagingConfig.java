package com.pesaguard.backend.outbox.infrastructure;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

/**
 * Redpanda producer wiring.
 *
 * <p>Entirely conditional on {@code pesaguard.messaging.enabled}. When messaging
 * is off no producer is created, {@code KafkaEventPublisher} is not registered,
 * and {@code OutboxRelay} finds no publisher and leaves events PENDING. That is
 * the safe direction: events accumulate in the database, which is exactly what
 * the outbox is for, instead of being marked published and lost.
 */
@Configuration
@ConditionalOnProperty(name = "pesaguard.messaging.enabled", havingValue = "true")
public class KafkaMessagingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessagingConfig.class);

    @Bean
    public MessagingTopics messagingTopics() {
        return new MessagingTopics();
    }

    @Bean
    public Producer<String, String> eventProducer(
            @Value("${pesaguard.messaging.bootstrap-servers}") String bootstrapServers,
            @Value("${pesaguard.messaging.acks:all}") String acks,
            @Value("${pesaguard.messaging.retries:3}") int retries,
            @Value("${pesaguard.messaging.request-timeout-ms:10000}") int requestTimeoutMs,
            @Value("${pesaguard.messaging.delivery-timeout-ms:30000}") int deliveryTimeoutMs,
            @Value("${pesaguard.messaging.max-block-ms:10000}") long maxBlockMs) {

        // The Kafka client enforces delivery.timeout.ms >= linger.ms +
        // request.timeout.ms, but only once a Producer is constructed. Checking it
        // here means an invalid pair fails at startup with a clear message rather
        // than the first time something tries to publish an event, which is
        // exactly when it is least convenient to diagnose.
        if (deliveryTimeoutMs <= requestTimeoutMs) {
            throw new IllegalStateException(
                    "pesaguard.messaging.delivery-timeout-ms (" + deliveryTimeoutMs
                            + ") must exceed request-timeout-ms (" + requestTimeoutMs
                            + "). The Kafka client requires delivery.timeout.ms to be at least"
                            + " linger.ms + request.timeout.ms, and rejects the producer otherwise.");
        }

        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // acks=all: an event the platform believes it published must survive a
        // broker restart. Combined with the outbox this is safe to choose:
        // losing an event is unrecoverable, a duplicate is handled downstream.
        config.put(ProducerConfig.ACKS_CONFIG, acks);
        config.put(ProducerConfig.RETRIES_CONFIG, retries);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        // Without this an unreachable broker blocks the calling thread
        // indefinitely inside send(). requestTimeoutMs does not bound that call,
        // so a broker outage would otherwise exhaust the relay's worker threads
        // and stall every instance's event publication.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        // Idempotence: the outbox retries after a failed send, and without this a
        // network timeout can leave a message on the broker that the retry then
        // duplicates.
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        log.info("Kafka producer configured for {} acks={} idempotent=true maxBlockMs={}",
                bootstrapServers, acks, maxBlockMs);
        // Typed factory: an untyped one infers Producer<Object, Object>, which
        // cannot be assigned to Producer<String, String>.
        return new DefaultKafkaProducerFactory<String, String>(config).createProducer();
    }
}