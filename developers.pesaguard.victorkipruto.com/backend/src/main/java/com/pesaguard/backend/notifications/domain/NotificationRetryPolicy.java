package com.pesaguard.backend.notifications.domain;

import java.time.Duration;

/**
 * Exponential backoff with full jitter for notification delivery.
 *
 * <p>Mirrors the webhook delivery policy deliberately: two subsystems retrying
 * independently would otherwise develop subtly different backoff, and the one
 * that gets it wrong is the one nobody tests.
 *
 * <p><b>Full jitter</b> draws the delay uniformly from {@code [0, ceiling]}. This
 * is not cosmetic. A notification storm — every user whose key expired in the
 * same maintenance window — would otherwise retry in lockstep and reproduce the
 * storm that caused the failures.
 */
public final class NotificationRetryPolicy {

    public static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(30);
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofHours(2);

    /**
     * Default attempt count.
     *
     * <p>Deliberately shorter than the webhook policy's eight. A notification the
     * user has not received after an hour is not going to arrive usefully, and
     * retrying longer mostly duplicates mail that lands late and out of order.
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final int maxAttempts;

    public NotificationRetryPolicy(Duration baseDelay, Duration maxDelay, int maxAttempts) {
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.maxAttempts = maxAttempts;
    }

    public static NotificationRetryPolicy defaults() {
        return new NotificationRetryPolicy(DEFAULT_BASE_DELAY, DEFAULT_MAX_DELAY,
                DEFAULT_MAX_ATTEMPTS);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration baseDelay() {
        return baseDelay;
    }

    public Duration maxDelay() {
        return maxDelay;
    }

    /**
     * Whether another attempt is allowed.
     *
     * @param attempt the number of attempts already made, 1-based
     */
    public boolean shouldRetry(int attempt) {
        return attempt < maxAttempts;
    }

    /**
     * Backoff ceiling for an attempt, before jitter.
     *
     * <p>Overflow-safe: a large attempt number would otherwise overflow the
     * multiplication and produce a negative duration, which would be an instant
     * retry storm.
     */
    public Duration ceilingFor(int attempt) {
        if (attempt <= 0) {
            return baseDelay;
        }
        long ceiling = baseDelay.toMillis();
        for (int index = 1; index < attempt; index++) {
            ceiling *= 2;
            if (ceiling >= maxDelay.toMillis()) {
                return maxDelay;
            }
        }
        return Duration.ofMillis(Math.max(Math.min(ceiling, maxDelay.toMillis()), 0L));
    }

    /**
     * The delay before the next attempt, with full jitter.
     *
     * @param random a value in {@code [0, 1)} from any source; injected so the
     *        behaviour is testable and so the caller controls the entropy
     */
    public Duration delayFor(int attempt, double random) {
        Duration ceiling = ceilingFor(attempt);
        double fraction = Math.max(0d, Math.min(0.999999d, random));
        return Duration.ofMillis((long) (ceiling.toMillis() * fraction));
    }
}
