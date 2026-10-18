package com.pesaguard.backend.analytics.algorithm.reliability;

import java.util.Random;

/**
 * Exponential backoff with jitter.
 *
 * <p>When a call to an external dependency fails, retrying immediately usually fails
 * again and can overwhelm an already-struggling provider. Exponential backoff spaces
 * retries further apart on each attempt, giving the dependency time to recover. Pure
 * exponential backoff has a flaw: if many callers fail simultaneously, their retries
 * synchronise into thundering-herd spikes at the same backoff multiples. Jitter -
 * randomising each delay - breaks that synchronisation.
 *
 * <p>Full jitter is used: the delay for attempt {@code n} is a random value in
 * {@code [0, min(cap, base * 2^n)]} rather than a fixed value plus noise. Full jitter
 * spreads retries across the whole interval and is the variant shown to minimise
 * collision probability. The delay is also capped so a long outage cannot produce an
 * absurdly long wait, and every delay is bounded by {@code capMillis}.
 *
 * <p>The random source is injectable so tests can assert on deterministic delays.
 */
public final class BackoffPolicy {

    private final long baseMillis;
    private final long capMillis;
    private final int maxAttempts;
    private final Random random;

    /**
     * @param baseMillis  delay unit for the first retry; doubles each attempt
     * @param capMillis   upper bound on any single delay
     * @param maxAttempts maximum number of attempts, including the first
     * @param random      jitter source; inject for deterministic tests
     */
    public BackoffPolicy(long baseMillis, long capMillis, int maxAttempts, Random random) {
        if (baseMillis <= 0) {
            throw new IllegalArgumentException("baseMillis must be positive");
        }
        if (capMillis < baseMillis) {
            throw new IllegalArgumentException("capMillis must be at least baseMillis");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.baseMillis = baseMillis;
        this.capMillis = capMillis;
        this.maxAttempts = maxAttempts;
        this.random = random == null ? new Random() : random;
    }

    /**
     * The delay to wait before the given attempt.
     *
     * @param attempt the 1-based attempt number about to be retried
     * @return a delay in {@code [0, capMillis]}
     */
    public long delayForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        // Exponential ceiling for this attempt, capped to keep the wait bounded.
        long exponential = baseMillis;
        for (int i = 1; i < attempt; i++) {
            exponential = Math.min(capMillis, exponential * 2);
            if (exponential == capMillis) {
                break;
            }
        }
        // Full jitter: uniform in [0, exponential].
        return Math.round(exponential * random.nextDouble());
    }

    /** Whether another attempt is permitted after {@code attemptsSoFar} failures. */
    public boolean shouldRetry(int attemptsSoFar) {
        return attemptsSoFar < maxAttempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
