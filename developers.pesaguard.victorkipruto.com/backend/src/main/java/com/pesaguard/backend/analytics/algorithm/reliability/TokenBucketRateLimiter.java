package com.pesaguard.backend.analytics.algorithm.reliability;

/**
 * Token-bucket rate limiter.
 *
 * <p>A token bucket holds up to {@code capacity} tokens, regenerates them at a steady
 * {@code refillTokensPerSecond} rate, and spends one token per permitted request. A
 * request is allowed only if a token is available; otherwise it is rejected. The
 * bucket shape gives two properties a simple fixed-window counter cannot: it tolerates
 * short bursts up to the bucket capacity, and it enforces a smooth long-run average
 * rate, so a caller cannot exceed the limit by timing requests at window edges.
 *
 * <p>Tokens are tracked in a double and refilled proportionally to elapsed time, so
 * the limiter is accurate at any call frequency without a background refill timer.
 * Time is passed in rather than read from a clock, which keeps the behaviour
 * deterministic and testable and lets the limiter be driven by event time if needed.
 *
 * <p>The limiter is per-key: a distinct instance (or a distinct key in a keyed map)
 * per tenant, endpoint, or MSISDN, so one caller's traffic cannot consume another's
 * budget. This is the same tenant-isolation discipline applied to throttling.
 *
 * <p>This limiter is not thread-safe; confine an instance to a single thread or
 * synchronise externally. That is intentional: it keeps the fast path allocation-free.
 */
public final class TokenBucketRateLimiter {

    private final double capacity;
    private final double refillTokensPerSecond;
    private double availableTokens;
    private long lastRefillMillis;

    /**
     * @param capacity               maximum burst size (bucket size)
     * @param refillTokensPerSecond  sustained rate at which tokens regenerate
     */
    public TokenBucketRateLimiter(double capacity, double refillTokensPerSecond) {
        if (capacity <= 0.0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (refillTokensPerSecond <= 0.0) {
            throw new IllegalArgumentException("refillTokensPerSecond must be positive");
        }
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        // Start full so a limiter does not penalise a caller that has been idle.
        this.availableTokens = capacity;
    }

    /**
     * Attempt to consume {@code tokens} at the given time.
     *
     * @param tokens number of tokens to spend (typically 1)
     * @param nowMillis current time in milliseconds
     * @return {@code true} if the tokens were available and have been spent
     */
    public boolean tryConsume(double tokens, long nowMillis) {
        refill(nowMillis);
        if (availableTokens >= tokens) {
            availableTokens -= tokens;
            return true;
        }
        return false;
    }

    /** Attempt to consume a single token. */
    public boolean tryConsume(long nowMillis) {
        return tryConsume(1.0, nowMillis);
    }

    /** Refill the bucket based on elapsed time, capped at capacity. */
    private void refill(long nowMillis) {
        if (lastRefillMillis == 0L) {
            lastRefillMillis = nowMillis;
            return;
        }
        long elapsedMillis = nowMillis - lastRefillMillis;
        if (elapsedMillis <= 0L) {
            // Clock did not advance; nothing to refill and no negative drift.
            return;
        }
        double refilled = (elapsedMillis / 1000.0) * refillTokensPerSecond;
        availableTokens = Math.min(capacity, availableTokens + refilled);
        lastRefillMillis = nowMillis;
    }

    public double availableTokens() {
        return availableTokens;
    }

    public double capacity() {
        return capacity;
    }
}
