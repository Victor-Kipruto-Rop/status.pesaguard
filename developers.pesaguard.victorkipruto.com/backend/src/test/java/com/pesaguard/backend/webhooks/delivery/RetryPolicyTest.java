package com.pesaguard.backend.webhooks.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Retry scheduling.
 *
 * <p>The property that matters most is the jitter: without it a PesaGuard outage
 * produces a synchronised retry storm against every integrator at once, which is a
 * self-inflicted outage caused by the recovery mechanism.
 */
class RetryPolicyTest {

    private final RetryPolicy policy = RetryPolicy.defaults();

    @Test
    void backoffGrowsExponentiallyUpToTheCap() {
        Duration first = policy.ceilingFor(1);
        Duration third = policy.ceilingFor(3);
        Duration seventh = policy.ceilingFor(7);

        assertThat(first).isEqualTo(Duration.ofSeconds(60));
        assertThat(third).isEqualTo(Duration.ofSeconds(240));
        assertThat(third).isGreaterThan(first);
        assertThat(seventh).isGreaterThan(third);
    }

    @Test
    void backoffIsCapped() {
        // Without a cap, 2^attempt eventually exceeds what any client will wait
        // and the delivery is abandoned before its attempts are spent.
        assertThat(policy.ceilingFor(100)).isEqualTo(policy.maxDelay());
    }

    @Test
    void aLargeAttemptNumberDoesNotOverflowIntoThePast() {
        // 1L << attempt would overflow past attempt 62 and produce a negative
        // duration, scheduling a retry in the past.
        Duration ceiling = policy.ceilingFor(Integer.MAX_VALUE);

        assertThat(ceiling).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(ceiling).isEqualTo(policy.maxDelay());
    }

    @Test
    void theFirstAttemptHasTheBaseDelayAsItsCeiling() {
        assertThat(policy.ceilingFor(0)).isEqualTo(policy.baseDelay());
    }

    @Test
    void jitterKeepsTheDelayWithinTheWindow() {
        Random random = new Random(42);

        for (int attempt = 1; attempt <= 8; attempt++) {
            for (int trial = 0; trial < 50; trial++) {
                Duration delay = policy.delayFor(attempt, random);
                assertThat(delay).isBetween(Duration.ZERO, policy.ceilingFor(attempt));
            }
        }
    }

    @Test
    void jitterActuallyVariesTheDelay() {
        // A test that only checks the bounds would pass even with no jitter at
        // all, which is the bug this exists to prevent.
        Random random = new Random(7);
        java.util.Set<Duration> delays = new java.util.HashSet<>();
        for (int trial = 0; trial < 50; trial++) {
            delays.add(policy.delayFor(4, random));
        }

        assertThat(delays).hasSizeGreaterThan(10);
    }

    @Test
    void retriesStopAtTheAttemptBudget() {
        assertThat(policy.shouldRetry(0)).isTrue();
        assertThat(policy.shouldRetry(policy.maxAttempts() - 1)).isTrue();
        assertThat(policy.shouldRetry(policy.maxAttempts())).isFalse();
        assertThat(policy.shouldRetry(policy.maxAttempts() + 5)).isFalse();
    }

    @Test
    void successIsNotRetried() {
        assertThat(policy.isRetryableStatus(200)).isFalse();
        assertThat(policy.isRetryableStatus(201)).isFalse();
        assertThat(policy.isRetryableStatus(204)).isFalse();
    }

    @Test
    void serverErrorsAreRetried() {
        assertThat(policy.isRetryableStatus(500)).isTrue();
        assertThat(policy.isRetryableStatus(502)).isTrue();
        assertThat(policy.isRetryableStatus(503)).isTrue();
    }

    @Test
    void clientErrorsAreNotRetried() {
        // Retrying an identical request that was rejected wastes the attempt budget
        // and hammers the receiver.
        assertThat(policy.isRetryableStatus(400)).isFalse();
        assertThat(policy.isRetryableStatus(401)).isFalse();
        assertThat(policy.isRetryableStatus(403)).isFalse();
        assertThat(policy.isRetryableStatus(422)).isFalse();
    }

    @Test
    void timingRelatedClientErrorsAreRetried() {
        // 408 and 429 are about timing, not about the request being malformed.
        assertThat(policy.isRetryableStatus(408)).isTrue();
        assertThat(policy.isRetryableStatus(429)).isTrue();
    }

    @Test
    void goneIsPermanent() {
        // 410 means the endpoint has been deleted; retrying is pointless.
        assertThat(policy.isRetryableStatus(410)).isFalse();
    }

    @Test
    void transportFailuresAlwaysRetry() {
        // A DNS failure or timeout says nothing about the receiver's intent.
        assertThat(policy.isRetryableTransportFailure()).isTrue();
    }
}