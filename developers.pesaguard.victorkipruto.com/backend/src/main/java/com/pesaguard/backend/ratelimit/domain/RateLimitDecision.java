package com.pesaguard.backend.ratelimit.domain;

import java.time.Instant;

/**
 * The outcome of one limit evaluation, and exactly what a developer is told.
 *
 * <p>All four developer-visible values are carried here — limit, remaining,
 * reset, and retry-after — so the decision and the headers cannot drift apart.
 * A client that is told "you have 0 left" but not "come back in 12 seconds" has
 * been told an unusable half-truth.
 */
public record RateLimitDecision(
        boolean allowed,
        int limit,
        int remaining,
        Instant resetAt,
        long retryAfterSeconds,
        RateLimitScope scope) {

    /**
     * The decision that applies when no limit is configured.
     *
     * <p>Reports a limit of -1 so a client can tell "unlimited" from "you have
     * 0 remaining". Conflating those two would make an unlimited integration
     * believe it had been throttled.
     */
    public static final int UNLIMITED = -1;

    public static RateLimitDecision allow(int limit, int remaining, Instant resetAt,
            RateLimitScope scope) {
        return new RateLimitDecision(true, limit, Math.max(0, remaining), resetAt, 0L, scope);
    }

    public static RateLimitDecision deny(int limit, Instant resetAt, long retryAfterSeconds,
            RateLimitScope scope) {
        return new RateLimitDecision(false, limit, 0, resetAt,
                Math.max(1L, retryAfterSeconds), scope);
    }

    /** No limit configured for this scope. */
    public static RateLimitDecision unlimited() {
        return new RateLimitDecision(true, UNLIMITED, UNLIMITED, null, 0L, null);
    }

    public boolean isUnlimited() {
        return limit == UNLIMITED;
    }

    /**
     * Seconds until the counter resets, never negative.
     *
     * <p>0 rather than a negative number when the reset is in the past, so a
     * client computing a wait never ends up with a negative delay.
     */
    public long resetAfterSeconds(Instant now) {
        if (resetAt == null || now == null) {
            return 0L;
        }
        return Math.max(0L, resetAt.getEpochSecond() - now.getEpochSecond());
    }
}