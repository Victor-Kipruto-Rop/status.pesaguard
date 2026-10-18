package com.pesaguard.backend.ratelimit.domain;

/**
 * The limiting algorithm, which decides how a counter behaves over time.
 *
 * <p>The two differ in a way a developer will notice, so the choice is exposed
 * rather than hidden behind a default.
 */
public enum RateLimitAlgorithm {

    /**
     * Tokens accrue continuously at the configured rate, up to a burst ceiling.
     *
     * <p>Allows bursts and then settles to a steady rate. This is the right choice
     * for protecting a backend from a spike: the ceiling bounds the worst case
     * while the sustained rate stays predictable.
     */
    TOKEN_BUCKET,

    /**
     * A rolling count over the last window, with no burst allowance beyond it.
     *
     * <p>Smoother and easier to reason about for daily and monthly quotas, where
     * "how much have I used in the last 24 hours" is the honest question. A token
     * bucket would let a client spend a full burst at midnight and again at
     * midnight the next day, which is not what a daily quota means to anyone.
     */
    SLIDING_WINDOW
}