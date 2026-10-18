package com.pesaguard.backend.analytics.algorithm.reliability;

/**
 * Token-bucket rate limiter.
 *
 * <p>A token bucket holds up to {@code capacity} tokens. Each permitted action spends
 * one token; tokens are replenished at a fixed {@code refillPerSecond} rate up to the
 * cap. A caller is allowed only when a token is available, so the limiter enforces a
 * sustained average rate while tolerating short bursts up to the bucket capacity -
 * a burst of legitimate traffic is not punished, but a sustained flood is throttled.
 *
 * <p>The limiter is refilled lazily: rather than a background thread adding tokens, the
 * elapsed time is applied on each {@link #tryAcquire} call. This keeps it allocation-
 * and thread-free and makes the rate exact regardless of how often it is polled.
 *
 * <p>Time is passed in, not read from a clock, so the behaviour is deterministic under
 * test and can be driven by event time in a stream. Like the other reliability
 * primitives it holds mutable state and is not thread-safe; confine one instance to a
 * single key and thread.
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerSecond;
    private double availableTokens;
    private long lastRefillMillis;

    /**
     * @param capacity          maximum burst size and the bucket's token ceiling
     * @param refillPerSecond   sustained rate at which tokens regenerate
     * @param nowMillis         current time used to seed the first refill
     */
    public TokenBucket(double capacity, double refillPerSecond, long nowMillis) {
        if (capacity <= 0.0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (refillPerSecond <= 0.0) {
            throw new IllegalArgumentException("refillPerSecond must be positive");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.availableTokens = capacity; // start full so an idle limiter allows a burst
        this.lastRefillMillis = nowMillis;
    }

    /**
     * Attempt to consume one token.
     *
     * @param nowMillis current time
     * @return {@code true} if a token was available and consumed, {@code false} if the
     *         caller must be rejected or delayed
     */
    public boolean tryAcquire(long nowMillis) {
        refill(nowMillis);
        if (availableTokens >= 1.0) {
            availableTokens -= 1.0;
            return true;
        }
        return false;
    }

    /**
     * Add tokens for the time elapsed since the last refill, capped at capacity.
     *
     * <p>Refill is proportional to elapsed seconds and never overshoots the cap, so
     * tokens cannot accumulate without bound during a long idle period - the rate stays
     * a true rate, not a stockpile.
     */
    private void refill(long nowMillis) {
        long elapsed = Math.max(0L, nowMillis - lastRefillMillis);
        if (elapsed == 0L) {
            return;
        }
        double tokensToAdd = (elapsed / 1000.0) * refillPerSecond;
        availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
        lastRefillMillis = nowMillis;
    }

    /** Tokens currently available, after applying any pending refill. */
    public double availableTokens(long nowMillis) {
        refill(nowMillis);
        return availableTokens;
    }
}
