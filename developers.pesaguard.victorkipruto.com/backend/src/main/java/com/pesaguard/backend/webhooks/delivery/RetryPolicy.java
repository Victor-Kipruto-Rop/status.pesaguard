package com.pesaguard.backend.webhooks.delivery;

import java.time.Duration;
import java.util.Random;

/**
 * Retry schedule for a failed webhook delivery.
 *
 * <p>Exponential backoff with <b>full jitter</b>: the delay is drawn uniformly from
 * {@code [0, min(cap, base * 2^attempt)]} rather than being exactly
 * {@code base * 2^attempt}.
 *
 * <p>Jitter is not cosmetic. Without it, every endpoint that failed at the same
 * moment retries at the same moment — after a PesaGuard incident, every integrator
 * in the country retries in lockstep, and the retry storm takes down the platforms
 * they are retrying against. That is a self-inflicted outage caused by the very
 * mechanism meant to help recovery. Full jitter also prevents many deliveries to
 * one endpoint from synchronising with each other.
 *
 * <p>The cap bounds the tail: without it, {@code 2^attempt} eventually overflows
 * the delay a client will wait, and the delivery is abandoned before the attempt
 * budget is spent.
 */
public final class RetryPolicy {

    public static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(30);
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofHours(6);
    public static final int DEFAULT_MAX_ATTEMPTS = 8;

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final int maxAttempts;

    public RetryPolicy(Duration baseDelay, Duration maxDelay, int maxAttempts) {
        this.baseDelay = baseDelay == null ? DEFAULT_BASE_DELAY : baseDelay;
        this.maxDelay = maxDelay == null ? DEFAULT_MAX_DELAY : maxDelay;
        this.maxAttempts = maxAttempts <= 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(DEFAULT_BASE_DELAY, DEFAULT_MAX_DELAY, DEFAULT_MAX_ATTEMPTS);
    }

    /** Attempts are 1-based: the first try is attempt 1. */
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
     * Whether another attempt is permitted.
     *
     * <p>{@code attempt} is how many attempts have already been made, so 0 means
     * the first try has not happened yet.
     */
    public boolean shouldRetry(int attempt) {
        return attempt < maxAttempts;
    }

    /**
     * The upper bound of the backoff window for a given attempt, before jitter.
     *
     * <p>Computed with a shift capped at 30 to avoid overflow. An attempt number
     * large enough to overflow would otherwise produce a negative duration and a
     * retry scheduled in the past.
     */
    public Duration ceilingFor(int attempt) {
        if (attempt <= 0) {
            return baseDelay;
        }
        long baseMillis = baseDelay.toMillis();
        int shift = Math.min(attempt, 30);
        long multiplier = 1L << shift;
        // Clamp before multiplying so a large base cannot overflow either.
        long ceiling;
        if (baseMillis > maxDelay.toMillis() / multiplier) {
            ceiling = maxDelay.toMillis();
        } else {
            ceiling = Math.min(baseMillis * multiplier, maxDelay.toMillis());
        }
        return Duration.ofMillis(Math.max(ceiling, 0));
    }

    /**
     * The actual delay for an attempt, with full jitter applied.
     *
     * @param random source of randomness; injected so the policy is testable and
     *        so a caller can seed it deterministically in tests
     */
    public Duration delayFor(int attempt, Random random) {
        long ceilingMillis = ceilingFor(attempt).toMillis();
        if (ceilingMillis <= 0) {
            return Duration.ZERO;
        }
        // Full jitter: uniform over the whole window. A random value in
        // [0, ceiling] rather than exactly ceiling, which is what makes a
        // synchronised retry storm impossible.
        long jittered = (long) (random.nextDouble() * ceilingMillis);
        return Duration.ofMillis(jittered);
    }

    public Duration delayFor(int attempt) {
        return delayFor(attempt, new Random());
    }

    /**
     * Whether a response code is worth retrying.
     *
     * <p>2xx is success. 410 Gone is permanent by definition, and 4xx other than
     * 408 and 429 indicate the request itself is wrong — retrying an identical
     * request that was rejected will be rejected again, and doing so wastes the
     * attempt budget and hammers the receiver.
     *
     * <p>408 (timeout) and 429 (too many requests) are retried because they are
     * explicitly about timing, not about the request being malformed.
     */
    public boolean isRetryableStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return false;
        }
        if (statusCode == 408 || statusCode == 429) {
            return true;
        }
        if (statusCode == 410) {
            return false;
        }
        if (statusCode >= 400 && statusCode < 500) {
            return false;
        }
        // 5xx and anything else unexpected: the receiver may well recover.
        return true;
    }

    /** A network error, timeout or DNS failure: always worth another attempt. */
    public boolean isRetryableTransportFailure() {
        return true;
    }
}