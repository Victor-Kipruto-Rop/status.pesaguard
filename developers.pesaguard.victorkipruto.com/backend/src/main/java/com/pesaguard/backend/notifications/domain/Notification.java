package com.pesaguard.backend.notifications.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One notification and its per-channel delivery state.
 *
 * <p>Channels are tracked <b>independently</b>. A notification whose email failed
 * but whose in-app record succeeded is not "failed" overall, and treating it as
 * such would hide a delivered message and retry it forever. Each channel carries
 * its own state, attempts, and error.
 */
public record Notification(
        UUID id,
        UUID organizationId,
        UUID userId,
        NotificationType type,
        String subject,
        String body,
        Instant createdAt,
        Map<NotificationChannel, ChannelDelivery> deliveries) {

    /**
     * Tracks one channel's progress.
     *
     * @param state where it stands
     * @param attempts number of attempts made so far
     * @param nextAttemptAt when to try again, null unless retrying
     * @param lastError the most recent failure, sanitised and bounded
     */
    public record ChannelDelivery(
            NotificationChannel channel,
            DeliveryState state,
            int attempts,
            Instant nextAttemptAt,
            String lastError) {

        public ChannelDelivery {
            if (attempts < 0) {
                throw new IllegalArgumentException("attempts cannot be negative");
            }
        }
    }

    public Notification {
        if (organizationId == null) {
            // Defence in depth: an unowned notification could be read by the wrong
            // tenant, and delivery could go to the wrong recipient.
            throw new IllegalArgumentException("A notification requires an organization");
        }
        if (userId == null) {
            throw new IllegalArgumentException("A notification requires a recipient");
        }
        if (type == null) {
            throw new IllegalArgumentException("A notification requires a type");
        }
        deliveries = Map.copyOf(deliveries);
    }

    /**
     * Creates a notification addressed to the channels the user wants.
     *
     * <p>Channels not wanted are recorded as SUPPRESSED rather than omitted, so
     * a user who later asks "why was I not told?" can be shown that they had
     * opted out.
     */
    public static Notification addressedTo(UUID organizationId, UUID userId, NotificationType type,
            String subject, String body, NotificationPreferences preferences,
            NotificationChannel... wanted) {
        if (preferences == null) {
            throw new IllegalArgumentException("Preferences are required");
        }
        Map<NotificationChannel, ChannelDelivery> deliveries = new LinkedHashMap<>();
        for (NotificationChannel channel : NotificationChannel.values()) {
            boolean enabled = preferences.isEnabled(type, channel);
            boolean requested = java.util.Arrays.asList(wanted).contains(channel);
            if (enabled && requested) {
                deliveries.put(channel, new ChannelDelivery(channel, DeliveryState.PENDING, 0,
                        null, null));
            } else {
                deliveries.put(channel, new ChannelDelivery(channel, DeliveryState.SUPPRESSED, 0,
                        null, null));
            }
        }
        return new Notification(UUID.randomUUID(), organizationId, userId, type, subject, body,
                null, deliveries);
    }

    /** Whether every requested channel has reached a terminal state. */
    public boolean isComplete() {
        return deliveries.values().stream().allMatch(delivery -> delivery.state().isTerminal());
    }

    /** Whether any channel is waiting for a retry. */
    public boolean hasPendingRetry() {
        return deliveries.values().stream()
                .anyMatch(delivery -> delivery.state() == DeliveryState.RETRY_SCHEDULED);
    }

    /** Whether the notification failed on every requested channel. */
    public boolean isFullyFailed() {
        return deliveries.values().stream()
                .filter(delivery -> delivery.state() != DeliveryState.SUPPRESSED)
                .allMatch(delivery -> delivery.state() == DeliveryState.FAILED);
    }

    public Notification withAttempt(UUID id, Instant createdAt,
            Map<NotificationChannel, ChannelDelivery> updated) {
        return new Notification(id, organizationId, userId, type, subject, body, createdAt,
                updated);
    }

    public Notification withDeliveries(Map<NotificationChannel, ChannelDelivery> updated) {
        return new Notification(id, organizationId, userId, type, subject, body, createdAt,
                updated);
    }

    /**
     * Advances one channel after a failed attempt.
     *
     * <p>Schedules a retry when the policy allows one, and marks it permanently
     * failed otherwise. The error is truncated because it may contain provider
     * detail that is useful in logs but does not belong in a user-visible record.
     */
    public Notification afterFailure(NotificationChannel channel, String error, int attempt,
            Duration delay, boolean willRetry, Instant now) {
        Map<NotificationChannel, ChannelDelivery> updated = new LinkedHashMap<>(deliveries);
        DeliveryState state = willRetry ? DeliveryState.RETRY_SCHEDULED : DeliveryState.FAILED;
        Instant nextAt = willRetry ? now.plus(delay) : null;
        updated.put(channel, new ChannelDelivery(channel, state, attempt, nextAt,
                sanitiseError(error)));
        return withDeliveries(updated);
    }

    public Notification afterSuccess(NotificationChannel channel, int attempt, Instant now,
            DeliveryState successState) {
        if (successState != DeliveryState.ACCEPTED && successState != DeliveryState.DELIVERED) {
            throw new IllegalArgumentException("A successful delivery requires a success state");
        }
        Map<NotificationChannel, ChannelDelivery> updated = new LinkedHashMap<>(deliveries);
        updated.put(channel, new ChannelDelivery(channel, successState, attempt, null,
                null));
        return withDeliveries(updated);
    }

    public Notification inProgress(NotificationChannel channel, int attempt, Instant now) {
        Map<NotificationChannel, ChannelDelivery> updated = new LinkedHashMap<>(deliveries);
        updated.put(channel, new ChannelDelivery(channel, DeliveryState.IN_PROGRESS, attempt,
                null, null));
        return withDeliveries(updated);
    }

    private static String sanitiseError(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }
        String trimmed = error.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }
}