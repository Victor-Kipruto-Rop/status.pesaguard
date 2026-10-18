package com.pesaguard.backend.ratelimit.domain;

/**
 * A token bucket: the state and the arithmetic, with no storage concerns.
 *
 * <p>Kept free of Redis, the database, and the clock so the arithmetic can be
 * tested exhaustively and reused unchanged by whichever store is in use. A
 * distributed store must compute the same answer as a local one, or a limit means
 * different things on different nodes.
 *
 * <p>State is a token count and the instant it was last refilled. Refill is
 * computed from elapsed time rather than by a background task, so a bucket that
 * has gone untouched for an hour correctly reports a full bucket on its next
 * check.
 */
public record TokenBucket(double tokens, long lastRefillEpochMillis) {

    /**
     * How many tokens accrue per millisecond.
     *
     * <p>Computed from the refill period and ceiling rather than stored, so
     * changing a policy's rate takes effect on the next request instead of after
     * the old bucket happens to expire.
     */
    public double tokensPerMillis(int limit, long windowMillis) {
        if (limit <= 0 || windowMillis <= 0) {
            return 0d;
        }
        return (double) limit / (double) windowMillis;
    }

    /**
     * The bucket as it stands {@code now}, with accrued tokens applied.
     *
     * <p>A clock that moves backwards yields no tokens. Granting capacity for
     * negative elapsed time would let a caller manufacture limit by moving the
     * clock, so backwards time is treated as no time at all.
     */
    public TokenBucket refilledTo(long nowEpochMillis, int limit, long windowMillis) {
        long elapsed = nowEpochMillis - lastRefillEpochMillis;
        if (elapsed <= 0) {
            return this;
        }
        double accrued = elapsed * tokensPerMillis(limit, windowMillis);
        // Clamped to the ceiling: a bucket that has been idle for a week is full,
        // not worth a week's worth of requests.
        return new TokenBucket(Math.min(limit, tokens + accrued), nowEpochMillis);
    }

    /**
     * Whether one request may proceed.
     *
     * <p>A single fractional token is not spendable. Without this check a caller
     * could fire requests faster than the configured rate forever and be refused
     * only once per fractional-token boundary, which is a rate of up to
     * {@code 1/tokenPerMillis} — far above the limit.
     */
    public boolean allows(long nowEpochMillis, int limit, long windowMillis) {
        return refilledTo(nowEpochMillis, limit, windowMillis).tokens >= 1d;
    }

    /**
     * Spends one token, or returns empty when the bucket is dry.
     *
     * <p>Returning the same instance when nothing was spent keeps a refused
     * request from rewriting the bucket, so a flood of refused requests does not
     * generate a write per request.
     */
    public java.util.Optional<TokenBucket> tryConsume(long nowEpochMillis, int limit,
            long windowMillis) {
        TokenBucket refilled = refilledTo(nowEpochMillis, limit, windowMillis);
        if (refilled.tokens < 1d) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new TokenBucket(refilled.tokens - 1d, nowEpochMillis));
    }

    /** Whole requests still available. Floored, never negative. */
    public int remaining(int limit, long windowMillis, long nowEpochMillis) {
        double available = refilledTo(nowEpochMillis, limit, windowMillis).tokens;
        if (available <= 0d) {
            return 0;
        }
        return (int) Math.min(limit, Math.floor(available));
    }

    /**
     * Milliseconds until one token is available.
     *
     * <p>At least 1, because a caller that retries after 0 ms is simply refused
     * again, and {@code Retry-After: 0} is not a useful instruction.
     */
    public long retryAfterMillis(int limit, long windowMillis, long nowEpochMillis) {
        TokenBucket refilled = refilledTo(nowEpochMillis, limit, windowMillis);
        if (refilled.tokens >= 1d) {
            return 0L;
        }
        double rate = tokensPerMillis(limit, windowMillis);
        if (rate <= 0d) {
            return Long.MAX_VALUE;
        }
        double needed = 1d - refilled.tokens;
        return Math.max(1L, (long) Math.ceil(needed / rate));
    }

    public static TokenBucket full(int limit, long nowEpochMillis) {
        return new TokenBucket(limit, nowEpochMillis);
    }

    public static TokenBucket empty(long nowEpochMillis) {
        return new TokenBucket(0d, nowEpochMillis);
    }
}