package com.pesaguard.backend.outbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Outbox state machine.
 *
 * <p>The properties tested here are the ones that keep an event from being
 * silently lost or silently duplicated.
 */
class OutboxEventTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private OutboxEvent newEvent() {
        return OutboxEvent.record(UUID.randomUUID(), "developer.project.created", 1,
                UUID.randomUUID(), UUID.randomUUID(), "partition-1",
                "corr-1", "trace-1", "{\"id\":\"p1\"}", "developer-platform", T0);
    }

    @Test
    void aNewEventIsPendingAndImmediatelyDue() {
        OutboxEvent event = newEvent();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        // Immediately due: a first attempt must not wait out a backoff window.
        assertThat(event.getNextAttemptAt()).isEqualTo(T0);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    void anEnvironmentScopedEventKeepsItsEnvironment() {
        UUID environmentId = UUID.randomUUID();

        OutboxEvent event = OutboxEvent.record(UUID.randomUUID(), "developer.api_key.created", 1,
                UUID.randomUUID(), UUID.randomUUID(), environmentId, "partition-1",
                "corr-1", "trace-1", "{\"environmentId\":\"" + environmentId + "\"}",
                "developer-platform", T0);

        assertThat(event.getEnvironmentId()).isEqualTo(environmentId);
    }

    @Test
    void publishingRecordsWhenItHappened() {
        OutboxEvent event = newEvent();
        Instant publishedAt = T0.plusSeconds(5);

        event.markPublished(publishedAt);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(publishedAt);
    }

    @Test
    void publishingTwiceIsRefusedSoTheOriginalTimestampSurvives() {
        OutboxEvent event = newEvent();
        event.markPublished(T0.plusSeconds(5));

        // Overwriting publishedAt would erase the only record of when the event
        // actually reached the broker.
        assertThatThrownBy(() -> event.markPublished(T0.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(event.getPublishedAt()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void aPublishedEventCannotLaterFail() {
        OutboxEvent event = newEvent();
        event.markPublished(T0);

        // A broker ack followed by a local failure must not be able to demote a
        // delivered event back to retryable and publish it again.
        assertThatThrownBy(() -> event.recordFailure("late error", T0, T0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> event.deadLetter("late error", T0))
                .isInstanceOf(IllegalStateException.class);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void aFailureSchedulesTheRetryLater() {
        OutboxEvent event = newEvent();
        Instant retryAt = T0.plusSeconds(30);

        event.recordFailure("broker unavailable", retryAt, T0);

        assertThat(event.getAttemptCount()).isOne();
        assertThat(event.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }
    @Test
    void aDeadLetterKeepsThePayloadSoItCanBeInvestigatedAndReplayed() {
        OutboxEvent event = newEvent();

        event.deadLetter("schema mismatch", T0);

        // A dead letter nobody can read is not a recovery capability.
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
        assertThat(event.getPayload()).isEqualTo("{\"id\":\"p1\"}");
        assertThat(event.getLastError()).isEqualTo("schema mismatch");
    }

    @Test
    void requeueResetsTheAttemptBudgetSoReplayIsPossible() {
        OutboxEvent event = newEvent();
        for (int attempt = 0; attempt < 9; attempt++) {
            event.recordFailure("still failing", T0.plusSeconds(30), T0);
        }
        event.deadLetter("exhausted", T0);
        assertThat(event.getAttemptCount()).isEqualTo(10);

        event.requeue(T0.plusSeconds(60));

        // Kept at 9+, an immediate requeue would dead-letter again on its first
        // failure and the event could never be replayed.
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void onlyADeadLetterCanBeRequeued() {
        OutboxEvent event = newEvent();

        assertThatThrownBy(() -> event.requeue(T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aLongErrorIsTruncatedRatherThanRejected() {
        OutboxEvent event = newEvent();

        event.recordFailure("x".repeat(5000), T0, T0);

        // The column is varchar(500). Truncating preserves the diagnostic value;
        // failing the write would lose the retry entirely.
        assertThat(event.getLastError()).hasSize(500);
    }

    @Test
    void eventIdentitySurvivesARetrySoAConsumerCanDeduplicate() {
        OutboxEvent event = newEvent();
        UUID identity = event.getEventId();

        event.recordFailure("transient", T0.plusSeconds(5), T0);
        event.markPublished(T0.plusSeconds(10));

        // At-least-once delivery means the same eventId may be seen twice. A
        // regenerated id would defeat consumer-side deduplication entirely.
        assertThat(event.getEventId()).isEqualTo(identity);
    }

    @Test
    void anEventVersionBelowOneIsRejected() {
        assertThatThrownBy(() -> OutboxEvent.record(UUID.randomUUID(),
                "developer.project.created", 0, UUID.randomUUID(), UUID.randomUUID(),
                "partition-1", null, null, "{}", "developer-platform", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingPartitionKeyIsRejectedAtWriteTime() {
        // Failing here rather than at publish time: an unroutable message
        // discovered after commit has no owner to fix it.
        assertThatThrownBy(() -> OutboxEvent.record(UUID.randomUUID(),
                "developer.project.created", 1, UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, "{}", "developer-platform", T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aPlatformEventNeedsNoTenant() {
        OutboxEvent event = OutboxEvent.record(UUID.randomUUID(),
                "developer.catalog.version_published", 1, null, null,
                "platform", null, null, "{}", "developer-platform", T0);

        assertThat(event.getOrganizationId()).isNull();
        assertThat(event.getPartitionKey()).isEqualTo("platform");
    }
}
