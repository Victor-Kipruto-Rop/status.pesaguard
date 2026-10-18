package com.pesaguard.backend.ratelimit.application;

import com.pesaguard.backend.ratelimit.domain.RateLimitDecision;
import com.pesaguard.backend.ratelimit.domain.RateLimitPolicy;

/**
 * Counter storage for rate limits and quotas.
 *
 * <p><b>This is the seam where Redis will be introduced.</b> The interface is
 * deliberately narrow and says nothing about storage, so an in-memory
 * implementation and a distributed one are interchangeable from the caller's
 * point of view.
 *
 * <p><b>Check-and-consume must be atomic.</b> A caller that reads a counter,
 * decides, and then writes it back has a race: two concurrent requests both read
 * "1 remaining" and both proceed, so the effective limit becomes 2. The
 * implementations therefore perform the whole evaluate-and-decrement in one
 * indivisible step. This is why the method takes a policy and returns a decision
 * rather than exposing a get and a put.
 */
public interface RateLimitCounterStore {

    /**
     * Evaluates a policy and consumes one unit if allowed, atomically.
     *
     * <p>Never throws for a limit breach — a breach is a returned decision. An
     * exception is reserved for genuine storage failure, and callers must decide
     * what that means (see {@link #isAvailable()}).
     */
    RateLimitDecision consume(RateLimitPolicy policy, String counterKey, long nowEpochMillis);

    /**
     * Reads a counter without consuming it.
     *
     * <p>For the developer-facing "how much have I got left" view. Must not
     * advance the counter, since a read is not a request.
     */
    RateLimitDecision peek(RateLimitPolicy policy, String counterKey, long nowEpochMillis);

    /**
     * Whether the store can currently serve requests.
     *
     * <p>The enforcing path fails closed when this method returns false. Keeping
     * availability explicit lets the service distinguish a configured unlimited
     * policy from a counter store that cannot enforce any policy.
     */
    boolean isAvailable();

    /** Drops counters for a key. Used when a credential or policy is deleted. */
    void reset(String counterKey);
}
