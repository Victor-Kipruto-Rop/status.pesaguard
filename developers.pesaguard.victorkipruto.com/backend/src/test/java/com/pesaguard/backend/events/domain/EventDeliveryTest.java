package com.pesaguard.backend.events.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EventDeliveryTest {

    private static EventSubscription subscription() {
        return EventSubscription.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID().toString(),
                EventType.tryParse("developer.project.created").orElseThrow(),
                1, null, Map.of());
    }

    @Test
    void retryAttemptsAreAppendOnlyAndAdvanceTheirAttemptNumber() {
        EventDelivery first = EventDelivery.firstAttempt(UUID.randomUUID(),
                "developer.project.created", subscription());
        Instant retryAt = Instant.parse("2026-10-05T12:00:00Z");
        first.markFailed(503, "HTTP_503", null, 12, retryAt, false);

        EventDelivery second = first.nextAttempt();

        assertThat(first.getStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        assertThat(first.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(second.getAttempt()).isEqualTo(2);
        assertThat(second.getStatus()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void permanentFailuresAndExhaustedRetriesAreDistinctOutcomes() {
        EventDelivery permanent = EventDelivery.firstAttempt(UUID.randomUUID(),
                "developer.project.created", subscription());
        permanent.markPermanentlyFailed(410, "HTTP_410", 8);
        EventDelivery exhausted = EventDelivery.firstAttempt(UUID.randomUUID(),
                "developer.project.created", subscription());
        exhausted.markFailed(503, "HTTP_503", null, 8, null, true);

        assertThat(permanent.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(exhausted.getStatus()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
    }
}
