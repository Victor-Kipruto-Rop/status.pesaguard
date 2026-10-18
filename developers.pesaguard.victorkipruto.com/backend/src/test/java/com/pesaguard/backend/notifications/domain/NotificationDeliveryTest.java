package com.pesaguard.backend.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Delivery state, retry policy, and the refusal to fake a send.
 *
 * <p>The central property: a notification that was not delivered must never
 * <em>report</em> as delivered. Quietly losing "your key was revoked" is the worst
 * failure this subsystem has, because the user discovers it from a failed
 * production payment instead.
 */
class NotificationDeliveryTest {

    private static final Instant T = Instant.parse("2026-03-15T14:37:52Z");
    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private Notification notification(NotificationType type) {
        return Notification.addressedTo(org, user, type, "Subject", "Body",
                NotificationPreferences.defaultsFor(user),
                NotificationChannel.EMAIL, NotificationChannel.IN_APP);
    }

    @Test
    void aNewNotificationIsPendingOnEveryChannel() {
        Notification notification = notification(NotificationType.API_KEY_REVOKED);

        assertThat(notification.deliveries().get(NotificationChannel.EMAIL).state())
                .isEqualTo(DeliveryState.PENDING);
        assertThat(notification.deliveries().get(NotificationChannel.IN_APP).state())
                .isEqualTo(DeliveryState.PENDING);
    }

    @Test
    void providerAcceptanceIsNotReportedAsInboxDelivery() {
        Notification accepted = notification(NotificationType.API_KEY_REVOKED)
                .afterSuccess(NotificationChannel.EMAIL, 1, T, DeliveryState.ACCEPTED);

        assertThat(accepted.deliveries().get(NotificationChannel.EMAIL).state())
                .isEqualTo(DeliveryState.ACCEPTED);
        assertThat(accepted.deliveries().get(NotificationChannel.EMAIL).attempts()).isEqualTo(1);
        // The other channel is untouched: channels are independent.
        assertThat(accepted.deliveries().get(NotificationChannel.IN_APP).state())
                .isEqualTo(DeliveryState.PENDING);
    }

    @Test
    void confirmedChannelDeliveryIsReportedAsDelivered() {
        Notification delivered = notification(NotificationType.API_KEY_REVOKED)
                .afterSuccess(NotificationChannel.IN_APP, 1, T, DeliveryState.DELIVERED);

        assertThat(delivered.deliveries().get(NotificationChannel.IN_APP).state())
                .isEqualTo(DeliveryState.DELIVERED);
    }

    @Test
    void aSuccessCannotUseAnUnconfirmedOrRetryState() {
        assertThatThrownBy(() -> notification(NotificationType.API_KEY_REVOKED)
                .afterSuccess(NotificationChannel.EMAIL, 1, T, DeliveryState.PENDING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRetryableFailureSchedulesRatherThanFails() {
        // Treating a transient fault as a permanent loss would abandon a security
        // notification over a momentary network blip.
        Notification failed = notification(NotificationType.CREDENTIAL_COMPROMISE)
                .afterFailure(NotificationChannel.EMAIL, "connection reset", 1,
                        Duration.ofSeconds(30), true, T);

        assertThat(failed.deliveries().get(NotificationChannel.EMAIL).state())
                .isEqualTo(DeliveryState.RETRY_SCHEDULED);
        assertThat(failed.hasPendingRetry()).isTrue();
        assertThat(failed.deliveries().get(NotificationChannel.EMAIL).nextAttemptAt())
                .isEqualTo(T.plusSeconds(30));
    }

    @Test
    void anExhaustedFailureIsTerminalAndNotSilentlyDropped() {
        Notification failed = notification(NotificationType.API_KEY_REVOKED)
                .afterFailure(NotificationChannel.EMAIL, "gave up", 5, Duration.ZERO,
                        false, T);

        assertThat(failed.deliveries().get(NotificationChannel.EMAIL).state())
                .isEqualTo(DeliveryState.FAILED);
        // The failure is retained so it is explainable, and visible as not-sent.
        assertThat(failed.deliveries().get(NotificationChannel.EMAIL).lastError())
                .isEqualTo("gave up");
        assertThat(failed.isFullyFailed()).isFalse();
    }

    @Test
    void aSuppressedChannelIsRecordedNotOmitted() {
        // A user who later asks "why was I not told?" must be shown they had opted
        // out, rather than finding no record at all.
        var preferences = NotificationPreferences.of(user,
                java.util.Map.of(NotificationCategory.USAGE,
                        java.util.EnumSet.of(NotificationChannel.EMAIL)));

        Notification notification = Notification.addressedTo(org, user,
                NotificationType.QUOTA_WARNING, "s", "b", preferences,
                NotificationChannel.EMAIL, NotificationChannel.IN_APP);

        assertThat(notification.deliveries().get(NotificationChannel.EMAIL).state())
                .isEqualTo(DeliveryState.SUPPRESSED);
        assertThat(notification.deliveries()).containsKey(NotificationChannel.EMAIL);
    }

    @Test
    void errorsAreBounded() {
        // A provider error may be long and may carry detail that does not belong in
        // a user-visible record.
        String huge = "x".repeat(5000);

        Notification failed = notification(NotificationType.API_KEY_REVOKED)
                .afterFailure(NotificationChannel.EMAIL, huge, 1, Duration.ZERO, false, T);

        assertThat(failed.deliveries().get(NotificationChannel.EMAIL).lastError())
                .hasSizeLessThanOrEqualTo(500);
    }

    @Test
    void aNotificationWithoutATenantCannotBeConstructed() {
        // Defence in depth: an unowned notification could be listed to the wrong
        // user, which is a data leak.
        assertThatThrownBy(() -> Notification.addressedTo(null, user,
                NotificationType.API_KEY_REVOKED, "s", "b",
                NotificationPreferences.defaultsFor(user), NotificationChannel.IN_APP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void backoffGrowsAndIsCapped() {
        NotificationRetryPolicy policy = NotificationRetryPolicy.defaults();

        assertThat(policy.ceilingFor(1)).isEqualTo(policy.baseDelay());
        assertThat(policy.ceilingFor(2)).isEqualTo(policy.baseDelay().multipliedBy(2));
        assertThat(policy.ceilingFor(50)).isEqualTo(policy.maxDelay());
    }

    @Test
    void aHugeAttemptNumberDoesNotOverflow() {
        // Without the cap, a large attempt would overflow the multiplication and
        // produce a negative duration, which is an instant retry storm.
        NotificationRetryPolicy policy = NotificationRetryPolicy.defaults();

        assertThat(policy.ceilingFor(Integer.MAX_VALUE).isNegative()).isFalse();
        assertThat(policy.ceilingFor(1000)).isEqualTo(policy.maxDelay());
    }

    @Test
    void fullJitterSpreadsRetries() {
        // A notification storm would otherwise retry in lockstep and reproduce the
        // storm that caused the failures.
        NotificationRetryPolicy policy = NotificationRetryPolicy.defaults();
        Duration ceiling = policy.ceilingFor(4);

        assertThat(policy.delayFor(4, 0.0d)).isEqualTo(Duration.ZERO);
        assertThat(policy.delayFor(4, 0.999999d)).isLessThanOrEqualTo(ceiling);
        assertThat(policy.delayFor(4, 0.5d)).isLessThan(ceiling);
    }

    @Test
    void jitterInputIsClamped() {
        NotificationRetryPolicy policy = NotificationRetryPolicy.defaults();

        assertThat(policy.delayFor(3, -5d).isNegative()).isFalse();
        assertThat(policy.delayFor(3, 42d).isNegative()).isFalse();
    }

    @Test
    void retriesStopAtTheAttemptLimit() {
        NotificationRetryPolicy policy = NotificationRetryPolicy.defaults();

        assertThat(policy.shouldRetry(1)).isTrue();
        assertThat(policy.shouldRetry(policy.maxAttempts() - 1)).isTrue();
        assertThat(policy.shouldRetry(policy.maxAttempts())).isFalse();
    }

    @Test
    void terminalStatesAreTerminal() {
        assertThat(DeliveryState.ACCEPTED.isTerminal()).isTrue();
        assertThat(DeliveryState.DELIVERED.isTerminal()).isTrue();
        assertThat(DeliveryState.FAILED.isTerminal()).isTrue();
        assertThat(DeliveryState.SUPPRESSED.isTerminal()).isTrue();
        assertThat(DeliveryState.PENDING.isTerminal()).isFalse();
        assertThat(DeliveryState.RETRY_SCHEDULED.isTerminal()).isFalse();
    }

    @Test
    void securityEventsAreRetryableButAQuotaWarningIsNot() {
        assertThat(NotificationType.CREDENTIAL_COMPROMISE.isRetryable()).isTrue();
        assertThat(NotificationType.API_KEY_REVOKED.isRetryable()).isTrue();
        // A quota warning that misses a cycle is not worth burning attempts on.
        assertThat(NotificationType.QUOTA_WARNING.isRetryable()).isFalse();
    }
}