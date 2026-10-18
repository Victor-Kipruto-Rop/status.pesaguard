package com.pesaguard.backend.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InvitationEmailDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void retriesUseIncreasingDelaysAndEraseCiphertextAfterFinalFailure() {
        InvitationEmailDelivery delivery = pendingDelivery();

        delivery.markFailed("MailSendException", NOW);
        assertThat(delivery.getStatus()).isEqualTo(InvitationEmailDeliveryStatus.PENDING);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(15));
        assertThat(delivery.getTokenCiphertext()).isNotNull();

        delivery.markFailed("MailSendException", NOW.plusSeconds(15));
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(45));

        for (int attempt = 3; attempt <= 6; attempt++) {
            delivery.markFailed("MailSendException", NOW.plusSeconds(45));
        }

        assertThat(delivery.getStatus()).isEqualTo(InvitationEmailDeliveryStatus.FAILED);
        assertThat(delivery.getAttemptCount()).isEqualTo(6);
        assertThat(delivery.getTokenCiphertext()).isNull();
    }

    @Test
    void cancellationErasesQueuedCiphertext() {
        InvitationEmailDelivery delivery = pendingDelivery();

        delivery.cancel();

        assertThat(delivery.getStatus()).isEqualTo(InvitationEmailDeliveryStatus.CANCELLED);
        assertThat(delivery.getTokenCiphertext()).isNull();
    }

    private InvitationEmailDelivery pendingDelivery() {
        return InvitationEmailDelivery.queue(UUID.randomUUID(), "member@example.com",
                "Organization", "Inviter", "DEVELOPER", "a".repeat(64),
                "encrypted-token", NOW.plusSeconds(86_400), NOW);
    }
}
