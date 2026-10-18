package com.pesaguard.backend.outbox.infrastructure;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.outbox.application.EventPublisher;
import com.pesaguard.backend.outbox.domain.OutboxEvent;

/**
 * Publishes outbox events to Redpanda over the Kafka protocol.
 *
 * <p>Only active when {@code pesaguard.messaging.enabled=true}. Default off, so a
 * deployment without a broker still starts and still records events; the relay
 * simply finds no publisher and leaves them PENDING for when one is configured.
 * Failing startup instead would mean the platform could not accept the very
 * events it would later need to deliver.
 *
 * <p>The producer key is {@code partitionKey}, which is what gives per-tenant
 * ordering: every event for one organization goes to one partition and is
 * therefore consumed in the order it was committed. A producer that published
 * with a random or null key would let a retry overtake the event it follows.
 *
 * <p>Errors are wrapped, never logged with the payload. The relay records only
 * the exception type against the row, and this class adds the event identity,
 * which is not sensitive.
 */
@Component
@ConditionalOnProperty(name = "pesaguard.messaging.enabled", havingValue = "true")
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final Producer<String, String> producer;
    private final MessagingTopics topics;

    /**
     * Binds the publisher to a single topic, bypassing the routing table.
     *
     * <p>Used by tests, which need an isolated topic per case. Production code
     * always routes through {@link MessagingTopics} so an event cannot reach a
     * topic outside the developer namespace.
     */
    KafkaEventPublisher(Producer<String, String> producer, String fixedTopic) {
        this(producer, new FixedTopic(fixedTopic));
    }

    /**
     * Pins every event to one topic.
     *
     * <p>Test-only. Production code always routes through {@link MessagingTopics}
     * so an event cannot reach a topic outside the developer namespace. A record
     * cannot be used here because {@link MessagingTopics} is a class.
     */
    private static final class FixedTopic extends MessagingTopics {

        private final String topic;

        private FixedTopic(String topic) {
            this.topic = topic;
        }

        @Override
        public String topicFor(String eventType) {
            return topic;
        }
    }

    public KafkaEventPublisher(Producer<String, String> producer, MessagingTopics topics) {
        this.producer = producer;
        this.topics = topics;
    }

    @Override
    public void publish(OutboxEvent event) {
        String envelope = OutboxEnvelope.from(event).toJson();
        try {
            RecordMetadata metadata = producer
                    .send(new org.apache.kafka.clients.producer.ProducerRecord<>(
                            topics.topicFor(event.getEventType()),
                            event.getPartitionKey(),
                            envelope))
                    .get();
            log.debug("Published eventId={} topic={} partition={} offset={}",
                    event.getEventId(), metadata.topic(), metadata.partition(), metadata.offset());
        } catch (InterruptedException interrupted) {
            // Never swallow an interrupt: it is the JVM being told to shut down,
            // and clearing the flag would make the shutdown itself uninterruptible.
            Thread.currentThread().interrupt();
            throw new EventPublishException("Publish interrupted");
        } catch (java.util.concurrent.ExecutionException | org.springframework.kafka.KafkaException failure) {
            throw new EventPublishException(
                    "Publish failed for event type " + event.getEventType(), failure);
        }
    }
}