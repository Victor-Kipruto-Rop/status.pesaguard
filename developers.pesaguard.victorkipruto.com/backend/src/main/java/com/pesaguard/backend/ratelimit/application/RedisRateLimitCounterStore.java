package com.pesaguard.backend.ratelimit.application;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.ratelimit.domain.RateLimitDecision;
import com.pesaguard.backend.ratelimit.domain.RateLimitPolicy;

/**
 * Distributed counter store backed by Redis.
 *
 * <p>This is what makes a rate limit mean the same thing on every instance. The
 * in-memory store is correct for one node and wrong for N: three instances each
 * enforce the limit independently, so a limit of 100/min admits up to 300/min.
 *
 * <p><b>The check and the decrement happen inside one Lua script.</b> That is not
 * an optimisation, it is the correctness requirement. A read-then-write
 * implementation has a race: two requests both read "1 remaining", both proceed,
 * and the effective limit doubles. Redis executes a script atomically, so there is
 * no window between deciding and charging.
 *
 * <p>Redis holds <b>last-millisecond timestamps</b>, not tokens. The token maths
 * then runs in Java against the timestamps the script returns. Refill-by-timestamp
 * is immune to the key expiring mid-window in a way that a stored token count is
 * not: an expired key simply means "nothing recorded", which is the same as a full
 * bucket.
 */
@Component
@ConditionalOnProperty(name = "pesaguard.ratelimit.store", havingValue = "redis")
public class RedisRateLimitCounterStore implements RateLimitCounterStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitCounterStore.class);

    /**
     * Appends the current timestamp to the window and returns the trimmed set.
     *
     * <p>Appends unconditionally and trims to the window, so the caller learns
     * whether the count is now over the limit. Doing it in this order means the
     * rejected request is also recorded: a client hammering a limited endpoint
     * should still consume its own budget, otherwise a flood would never be
     * counted and would look like low usage.
     *
     * <p>ARGV: nowMillis, windowMillis, capacity.
     * Returns: { used, oldestMillis }.
     */
    private static final String CONSUME_SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local capacity = tonumber(ARGV[3])
            local cutoff = now - window

            redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)
            redis.call('ZADD', key, now, tostring(now) .. ':' .. tostring(now))
            local used = redis.call('ZCARD', key)
            local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')

            -- Expire the key shortly after the window closes so a dormant counter
            -- does not occupy memory forever.
            redis.call('PEXPIRE', key, window + 1000)

            local oldestMillis = 0
            if #oldest > 1 then
                oldestMillis = tonumber(oldest[2])
            end
            return { used, oldestMillis }
            """;

    /** Reads the window size without recording anything. */
    private static final String PEEK_SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local cutoff = now - window
            redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)
            local used = redis.call('ZCARD', key)
            local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            local oldestMillis = 0
            if #oldest > 1 then
                oldestMillis = tonumber(oldest[2])
            end
            return { used, oldestMillis }
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> consumeScript;
    private final DefaultRedisScript<List> peekScript;

    public RedisRateLimitCounterStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.consumeScript = new DefaultRedisScript<>(CONSUME_SCRIPT, List.class);
        this.peekScript = new DefaultRedisScript<>(PEEK_SCRIPT, List.class);
    }

    @Override
    public RateLimitDecision consume(RateLimitPolicy policy, String counterKey, long now) {
        long window = policy.windowMillis();
        long capacity = capacityFor(policy);
        List<?> result = redis.execute(consumeScript, List.of(counterKey),
                String.valueOf(now), String.valueOf(window), String.valueOf(capacity));

        long used = asLong(result, 0);
        long oldest = asLong(result, 1);
        Instant resetAt = Instant.ofEpochMilli(oldest == 0 ? now + window : oldest + window);
        long retryMillis = Math.max(1L, resetAt.toEpochMilli() - now);

        if (used > capacity) {
            // Reported as a fraction of the configured limit so a caller sees the
            // same shape of answer whichever store is in use.
            return RateLimitDecision.deny(policy.limit(), resetAt,
                    (retryMillis + 999L) / 1000L, policy.scope());
        }
        return RateLimitDecision.allow(policy.limit(), (int) Math.max(0L, capacity - used),
                resetAt, policy.scope());
    }

    @Override
    public RateLimitDecision peek(RateLimitPolicy policy, String counterKey, long now) {
        List<?> result = redis.execute(peekScript, List.of(counterKey),
                String.valueOf(now), String.valueOf(policy.windowMillis()));
        long used = asLong(result, 0);
        long oldest = asLong(result, 1);
        long capacity = capacityFor(policy);
        Instant resetAt = Instant.ofEpochMilli(
                oldest == 0 ? now + policy.windowMillis() : oldest + policy.windowMillis());
        if (used >= capacity) {
            return RateLimitDecision.deny(policy.limit(), resetAt, 1L, policy.scope());
        }
        return RateLimitDecision.allow(policy.limit(), (int) Math.max(0L, capacity - used),
                resetAt, policy.scope());
    }

    /**
     * The effective capacity in a window.
     *
     * <p>A token bucket's burst is converted to a per-window allowance. The burst
     * ceiling is enforced separately by the configuration, not here: a sliding
     * window has no burst concept, and inventing one would let a client exceed a
     * limit it is configured not to.
     */
    private long capacityFor(RateLimitPolicy policy) {
        return policy.limit();
    }

    private long asLong(List<?> result, int index) {
        if (result == null || result.size() <= index || result.get(index) == null) {
            return 0L;
        }
        Object value = result.get(index);
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    @Override
    public boolean isAvailable() {
        try {
            redis.hasKey("rl:__health");
            return true;
        } catch (RuntimeException unavailable) {
            log.error("redis is unavailable; enforced requests will be rejected");
            return false;
        }
    }

    @Override
    public void reset(String counterKey) {
        redis.delete(counterKey);
    }
}
