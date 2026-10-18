package com.pesaguard.backend.ratelimit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.ratelimit.domain.RateLimitDecision;
import com.pesaguard.backend.ratelimit.domain.RateLimitPolicy;
import com.pesaguard.backend.ratelimit.domain.RateLimitScope;

/**
 * Store-level behaviour, including the two bugs these tests exist to prevent.
 *
 * <p>Both were real defects found by the Phase 13 suite: a fresh counter was
 * created empty, refusing the first request of every window; and the lock object
 * was replaced on each write, so two threads could hold different monitors for
 * the same key and check-and-consume was not atomic.
 */
class InMemoryRateLimitCounterStoreTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final Instant NOW = Instant.ofEpochMilli(T0);
    private final UUID org = UUID.randomUUID();

    private RateLimitPolicy policy(int limit, Integer burst) {
        return RateLimitPolicy.tokenBucket(UUID.randomUUID(), org, RateLimitScope.API_KEY,
                null, null, limit, Duration.ofMinutes(1), burst);
    }

    @Test
    void theFirstRequestOnAFreshCounterIsAllowed() {
        // A counter starting empty would refuse the first request of every window,
        // which presents as a broken API rather than as a limit.
        InMemoryRateLimitCounterStore store = new InMemoryRateLimitCounterStore();
        RateLimitPolicy policy = policy(3, null);

        RateLimitDecision first = store.consume(policy, "k1", T0);

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(2);
    }

    @Test
    void aFreshSlidingWindowCounterIsAlsoAllowed() {
        InMemoryRateLimitCounterStore store = new InMemoryRateLimitCounterStore();
        RateLimitPolicy policy = RateLimitPolicy.slidingWindow(UUID.randomUUID(), org,
                RateLimitScope.API_KEY, null, null, 3, Duration.ofMinutes(1));

        assertThat(store.consume(policy, "w1", T0).allowed()).isTrue();
    }

    @Test
    void peekDoesNotConsume() {
        // A developer dashboard polling usage must not spend their own quota.
        InMemoryRateLimitCounterStore store = new InMemoryRateLimitCounterStore();
        RateLimitPolicy policy = policy(3, null);

        store.consume(policy, "k1", T0);
        store.peek(policy, "k1", T0);
        store.peek(policy, "k1", T0);

        RateLimitDecision after = store.consume(policy, "k1", T0);
        assertThat(after.allowed()).isTrue();
        assertThat(after.remaining()).isEqualTo(1);
    }

    @Test
    void peekOnAnUnseenKeyReportsFullCapacity() {
        InMemoryRateLimitCounterStore store = new InMemoryRateLimitCounterStore();
        RateLimitPolicy policy = policy(5, null);

        assertThat(store.peek(policy, "never-seen", T0).remaining()).isEqualTo(5);
    }

    @Test
    void resetClearsAKey() {
        InMemoryRateLimitCounterStore store = new InMemoryRateLimitCounterStore();
        RateLimitPolicy policy = policy(1, null);

        store.consume(policy, "k1", T0);
        assertThat(store.consume(policy, "k1", T0).allowed()).isFalse();

        store.reset("k1");
        assertThat(store.consume(policy, "k1", T0).allowed()).isTrue();
    }

    @Test
    void differentKeysAreIndependent() {
        InMemoryRateLimitCounterStore store = new InMemoryRateLimitCounterStore();
        RateLimitPolicy policy = policy(1, null);

        assertThat(store.consume(policy, "a", T0).allowed()).isTrue();
        assertThat(store.consume(policy, "b", T0).allowed()).isTrue();
        assertThat(store.consume(policy, "a", T0).allowed()).isFalse();
    }

    @Test
    void concurrentConsumptionIsAtomic() throws Exception {
        // The regression test for the replaced-lock bug. A limit of 40 with 300
        // racing threads: a broken check-and-consume admits far more than 40, and
        // because the monitor was swapped on write the old code passed this by
        // luck on a single run.
        int limit = 40;
        int threads = 300;
        InMemoryRateLimitCounterStore store = new InMemoryRateLimitCounterStore();
        RateLimitPolicy policy = policy(limit, limit);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(24);

        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        if (store.consume(policy, "hot", T0).allowed()) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).isEqualTo(limit);
    }

    @Test
    void theStoreReportsItselfAvailable() {
        assertThat(new InMemoryRateLimitCounterStore().isAvailable()).isTrue();
    }
}