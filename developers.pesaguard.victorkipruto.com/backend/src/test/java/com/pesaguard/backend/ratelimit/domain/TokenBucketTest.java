package com.pesaguard.backend.ratelimit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Token bucket arithmetic.
 *
 * <p>The cases that matter are the ones where a naive implementation leaks
 * capacity: fractional tokens, a clock that moves backwards, and a bucket left
 * idle far longer than its window.
 */
class TokenBucketTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final int LIMIT = 10;
    private static final long WINDOW = 60_000L;

    @Test
    void aFullBucketAllowsUpToTheLimit() {
        TokenBucket bucket = TokenBucket.full(LIMIT, T0);

        for (int i = 0; i < LIMIT; i++) {
            assertThat(bucket.allows(T0, LIMIT, WINDOW)).isTrue();
            bucket = bucket.tryConsume(T0, LIMIT, WINDOW).orElseThrow();
        }
        assertThat(bucket.allows(T0, LIMIT, WINDOW)).isFalse();
    }

    @Test
    void tokensAccrueOverTime() {
        // Half a window refills half the limit.
        TokenBucket bucket = TokenBucket.empty(T0);
        assertThat(bucket.allows(T0 + 30_000L, LIMIT, WINDOW)).isTrue();
    }

    @Test
    void refillIsCappedAtTheBurstCeiling() {
        // Idle for a day: the bucket is full, not worth a day's requests.
        TokenBucket bucket = TokenBucket.empty(T0);
        TokenBucket refilled = bucket.refilledTo(T0 + 86_400_000L, LIMIT, WINDOW);
        assertThat(refilled.tokens()).isEqualTo(LIMIT);
    }

    @Test
    void aFractionalTokenIsNotSpendable() {
        // Without this, a caller could fire faster than the configured rate and
        // only be refused at fractional-token boundaries.
        TokenBucket bucket = TokenBucket.full(LIMIT, T0);
        for (int i = 0; i < LIMIT; i++) {
            bucket = bucket.tryConsume(T0, LIMIT, WINDOW).orElseThrow();
        }
        assertThat(bucket.tokens()).isEqualTo(0d);

        // 1ms of a 60s window at 10 tokens is a fraction of a token: not spendable.
        TokenBucket barely = bucket.refilledTo(T0 + 1L, LIMIT, WINDOW);
        assertThat(barely.allows(T0 + 1L, LIMIT, WINDOW)).isFalse();
    }

    @Test
    void aBackwardsClockGrantsNoTokens() {
        // Otherwise a caller could manufacture capacity by moving the clock.
        TokenBucket bucket = TokenBucket.empty(T0);
        assertThat(bucket.refilledTo(T0 - 60_000L, LIMIT, WINDOW).tokens()).isEqualTo(0d);
        assertThat(bucket.allows(T0 - 60_000L, LIMIT, WINDOW)).isFalse();
    }

    @Test
    void aZeroOrNegativeConfigurationGrantsNothing() {
        // A misconfigured limit must fail closed rather than divide by zero.
        TokenBucket bucket = TokenBucket.empty(T0);
        assertThat(bucket.tokensPerMillis(0, WINDOW)).isZero();
        assertThat(bucket.tokensPerMillis(LIMIT, 0L)).isZero();
        assertThat(bucket.retryAfterMillis(0, WINDOW, T0)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void remainingIsFlooredAndNeverNegative() {
        TokenBucket bucket = TokenBucket.empty(T0);
        assertThat(bucket.remaining(LIMIT, WINDOW, T0)).isZero();

        TokenBucket partial = new TokenBucket(3.7d, T0);
        assertThat(partial.remaining(LIMIT, WINDOW, T0)).isEqualTo(3);
    }

    @Test
    void retryAfterIsAtLeastOneMillisecond() {
        // A caller told to retry after 0ms is simply refused again.
        TokenBucket bucket = TokenBucket.empty(T0);
        assertThat(bucket.retryAfterMillis(LIMIT, WINDOW, T0)).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void retryAfterIsZeroWhenTokensRemain() {
        assertThat(TokenBucket.full(LIMIT, T0).retryAfterMillis(LIMIT, WINDOW, T0)).isZero();
    }

    @Test
    void consumeReturnsEmptyRatherThanGoingNegative() {
        TokenBucket bucket = TokenBucket.empty(T0);
        Optional<TokenBucket> spent = bucket.tryConsume(T0, LIMIT, WINDOW);
        assertThat(spent).isEmpty();
    }
}