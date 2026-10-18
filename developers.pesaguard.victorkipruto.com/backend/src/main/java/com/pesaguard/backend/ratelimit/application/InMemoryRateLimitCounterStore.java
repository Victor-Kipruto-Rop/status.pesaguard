package com.pesaguard.backend.ratelimit.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.ratelimit.domain.RateLimitDecision;
import com.pesaguard.backend.ratelimit.domain.RateLimitPolicy;
import com.pesaguard.backend.ratelimit.domain.SlidingWindow;
import com.pesaguard.backend.ratelimit.domain.TokenBucket;

/**
 * In-process counter store.
 *
 * <p><b>Not suitable for a multi-node deployment.</b> Each node keeps its own
 * counters, so a limit of 100/min behind three nodes admits up to 300/min. It is
 * correct for a single instance and for tests, and exists so the algorithms are
 * exercisable without new infrastructure. A distributed store is required before
 * this runs behind more than one instance.
 *
 * <p>Each key owns a {@link Counter} whose identity never changes. Locking is
 * per key, not global: synchronizing the map would make every request in the
 * system contend on one monitor, serialising the whole API on its rate limiter
 * under exactly the load the limiter exists to handle.
 */
@Component
@ConditionalOnProperty(name = "pesaguard.ratelimit.store", havingValue = "in-memory",
        matchIfMissing = true)
public class InMemoryRateLimitCounterStore implements RateLimitCounterStore {

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /**
     * One counter per key.
     *
     * <p>The object is created once and never replaced, so the monitor a thread
     * holds stays valid for the life of the counter. Replacing the state object
     * on every write would mean two concurrent threads could hold different
     * monitors for the same key, and the check-and-consume would no longer be
     * atomic.
     */
    private static final class Counter {
        private Object state;
    }

    @Override
    public RateLimitDecision consume(RateLimitPolicy policy, String counterKey,
            long nowEpochMillis) {
        Counter counter = counters.computeIfAbsent(counterKey, key -> {
            Counter created = new Counter();
            return created;
        });
        synchronized (counter) {
            if (policy.isTokenBucket()) {
                return consumeTokenBucket(policy, counter, nowEpochMillis);
            }
            return consumeSlidingWindow(policy, counter, nowEpochMillis);
        }
    }

    @Override
    public RateLimitDecision peek(RateLimitPolicy policy, String counterKey,
            long nowEpochMillis) {
        Counter counter = counters.get(counterKey);
        if (counter == null) {
            return fresh(policy, nowEpochMillis);
        }
        synchronized (counter) {
            return describe(policy, counter, nowEpochMillis);
        }
    }

    private RateLimitDecision consumeTokenBucket(RateLimitPolicy policy, Counter counter,
            long nowEpochMillis) {
        int capacity = policy.burstCapacity();
        long window = policy.windowMillis();

        // A counter that has never been used starts FULL, not empty. Starting it
        // empty would refuse the first request of every window, which reads as a
        // broken API rather than a limit.
        TokenBucket bucket = counter.state instanceof TokenBucket existing
                ? existing
                : TokenBucket.full(capacity, nowEpochMillis);

        Optional<TokenBucket> spent = bucket.tryConsume(nowEpochMillis, capacity, window);
        if (spent.isEmpty()) {
            TokenBucket refilled = bucket.refilledTo(nowEpochMillis, capacity, window);
            counter.state = refilled;
            long retryMillis = refilled.retryAfterMillis(capacity, window, nowEpochMillis);
            return deny(policy, nowEpochMillis + retryMillis, retryMillis);
        }

        counter.state = spent.get();
        return RateLimitDecision.allow(policy.limit(),
                spent.get().remaining(capacity, window, nowEpochMillis),
                Instant.ofEpochMilli(nowEpochMillis + window), policy.scope());
    }

    private RateLimitDecision consumeSlidingWindow(RateLimitPolicy policy, Counter counter,
            long nowEpochMillis) {
        long[] timestamps = counter.state instanceof long[] existing ? existing : new long[0];
        int used = SlidingWindow.countWithin(timestamps, nowEpochMillis, policy.windowMillis());
        if (used >= policy.limit()) {
            long retryMillis = SlidingWindow.retryAfterMillis(timestamps, nowEpochMillis,
                    policy.windowMillis());
            return deny(policy, nowEpochMillis + retryMillis, retryMillis);
        }
        long[] appended = SlidingWindow.append(timestamps, nowEpochMillis, policy.windowMillis());
        counter.state = appended;
        return RateLimitDecision.allow(policy.limit(), policy.limit() - appended.length,
                Instant.ofEpochMilli(nowEpochMillis + policy.windowMillis()), policy.scope());
    }

    /** Reads without consuming: a peek must not advance a counter. */
    private RateLimitDecision describe(RateLimitPolicy policy, Counter counter,
            long nowEpochMillis) {
        long window = policy.windowMillis();
        if (counter.state instanceof TokenBucket bucket) {
            int capacity = policy.burstCapacity();
            TokenBucket refilled = bucket.refilledTo(nowEpochMillis, capacity, window);
            int remaining = refilled.remaining(capacity, window, nowEpochMillis);
            if (remaining <= 0) {
                long retryMillis = refilled.retryAfterMillis(capacity, window, nowEpochMillis);
                return deny(policy, nowEpochMillis + retryMillis, retryMillis);
            }
            return RateLimitDecision.allow(policy.limit(), remaining,
                    Instant.ofEpochMilli(nowEpochMillis + window), policy.scope());
        }
        if (counter.state instanceof long[] timestamps) {
            int used = SlidingWindow.countWithin(timestamps, nowEpochMillis, window);
            if (used >= policy.limit()) {
                long retryMillis = SlidingWindow.retryAfterMillis(timestamps, nowEpochMillis,
                        window);
                return deny(policy, nowEpochMillis + retryMillis, retryMillis);
            }
            return RateLimitDecision.allow(policy.limit(), policy.limit() - used,
                    Instant.ofEpochMilli(nowEpochMillis + window), policy.scope());
        }
        return fresh(policy, nowEpochMillis);
    }

    private RateLimitDecision fresh(RateLimitPolicy policy, long nowEpochMillis) {
        return RateLimitDecision.allow(policy.limit(), policy.burstCapacity(),
                Instant.ofEpochMilli(nowEpochMillis + policy.windowMillis()), policy.scope());
    }

    private RateLimitDecision deny(RateLimitPolicy policy, long resetAtMillis, long retryMillis) {
        return RateLimitDecision.deny(policy.limit(), Instant.ofEpochMilli(resetAtMillis),
                secondsFrom(retryMillis), policy.scope());
    }

    /** Rounded up, and at least 1, so a client is never told to retry instantly. */
    private long secondsFrom(long millis) {
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void reset(String counterKey) {
        counters.remove(counterKey);
    }
}