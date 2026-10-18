package com.pesaguard.backend.outbox.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;

import com.pesaguard.backend.outbox.application.EventPublisher;
import com.pesaguard.backend.outbox.domain.OutboxEvent;

/**
 * Publish contract against a mocked producer.
 *
 * <p>Covers what must hold regardless of transport: the record is keyed for
 * ordering, routed to the right topic, and a broker failure is translated into
 * the exception the relay knows how to retry. A broker-backed test would also
 * prove the wire, but its harness is not reliable here and a mock still catches
 * the mistakes that actually happen: a wrong topic, a null key, or a swallowed
 * failure.
 */
class KafkaEventPublisherContractTest {

    @SuppressWarnings("unchecked")
    private final Producer<String, String> producer = mock(Producer.class);

    private final KafkaEventPublisher publisher =
            new KafkaEventPublisher(producer, new MessagingTopics());

    private static RecordMetadata acknowledged() {
        RecordMetadata metadata = mock(RecordMetadata.class);
        when(metadata.topic()).thenReturn("pesaguard.developer.project");
        when(metadata.partition()).thenReturn(0);
        when(metadata.offset()).thenReturn(1L);
        return metadata;
    }

    private OutboxEvent event(String type, String partitionKey) {
        return OutboxEvent.record(UUID.randomUUID(), type, 1,
                UUID.randomUUID(), null, partitionKey, "corr-1", "trace-1",
                "{\"id\":\"p1\"}", "developer-platform", Instant.now());
    }

    @Test
    void theRecordIsKeyedByPartitionKeySoOrderingSurvives() {
        CompletableFuture<RecordMetadata> ok = CompletableFuture.completedFuture(acknowledged());
        when(producer.send(any(ProducerRecord.class))).thenReturn(ok);

        publisher.publish(event("developer.project.created", "tenant-42"));

        org.mockito.ArgumentCaptor<ProducerRecord<String, String>> sent =
                org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(producer).send(sent.capture());

        // A null or random key would let a retry overtake the event it follows.
        assertThat(sent.getValue().key()).isEqualTo("tenant-42");
    }

    @Test
    void theTopicIsChosenByTheRoutingTable() {
        CompletableFuture<RecordMetadata> ok = CompletableFuture.completedFuture(acknowledged());
        when(producer.send(any(ProducerRecord.class))).thenReturn(ok);

        publisher.publish(event("developer.project.created", "tenant-42"));

        org.mockito.ArgumentCaptor<ProducerRecord<String, String>> sent =
                org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(producer).send(sent.capture());

        assertThat(sent.getValue().topic())
                .isEqualTo(MessagingTopics.DEVELOPER_PREFIX + "project");
    }

    @Test
    void aBrokerFailureBecomesTheExceptionTheRelayRetries() {
        CompletableFuture<RecordMetadata> failed =
                new CompletableFuture<>();
        failed.completeExceptionally(new org.apache.kafka.common.errors.TimeoutException("no broker"));
        when(producer.send(any(ProducerRecord.class))).thenReturn(failed);

        assertThatThrownBy(() -> publisher.publish(event("developer.project.created", "t")))
                .isInstanceOf(EventPublisher.EventPublishException.class);
    }
}
