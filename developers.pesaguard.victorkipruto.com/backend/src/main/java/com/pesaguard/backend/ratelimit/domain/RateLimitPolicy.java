package com.pesaguard.backend.ratelimit.domain;

import java.time.Duration;
import java.util.UUID;

/**
 * A configured limit: what is limited, by how much, over what period, and with
 * which algorithm.
 *
 * <p>Immutable. A policy is configuration a developer reads and relies on, so it
 * must not be mutable while a request is being evaluated against it — a limit
 * that changes mid-request would make the reported remaining count a fiction.
 *
 * <p>Uniqueness is enforced by the store, not here: the same scope may legitimately
 * have several policies, for example a per-endpoint override that sits alongside
 * an environment-wide default.
 */
public record RateLimitPolicy(
        UUID id,
        UUID organizationId,
        RateLimitScope scope,
        UUID subjectId,
        String endpointPattern,
        RateLimitAlgorithm algorithm,
        int limit,
        long windowMillis,
        int burstCapacity,
        boolean enabled) {

    /**
     * A token bucket.
     *
     * <p>{@code burstCapacity} is the ceiling; when omitted it defaults to the
     * limit, meaning a client may spend its whole minute's allowance at once but
     * no more than that in any burst.
     */
    public static RateLimitPolicy tokenBucket(UUID id, UUID organizationId, RateLimitScope scope,
            UUID subjectId, String endpointPattern, int limit, Duration window,
            Integer burstCapacity) {
        requirePositive(limit, "limit");
        requirePositiveWindow(window);
        int burst = burstCapacity == null ? limit : burstCapacity;
        if (burst < 1) {
            throw new IllegalArgumentException("burstCapacity must be at least 1");
        }
        return new RateLimitPolicy(id, organizationId, scope, subjectId, endpointPattern,
                RateLimitAlgorithm.TOKEN_BUCKET, limit, window.toMillis(), burst, true);
    }

    /** A sliding window. The burst capacity is always the limit. */
    public static RateLimitPolicy slidingWindow(UUID id, UUID organizationId,
            RateLimitScope scope, UUID subjectId, String endpointPattern, int limit,
            Duration window) {
        requirePositive(limit, "limit");
        requirePositiveWindow(window);
        return new RateLimitPolicy(id, organizationId, scope, subjectId, endpointPattern,
                RateLimitAlgorithm.SLIDING_WINDOW, limit, window.toMillis(), limit, true);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }

    private static void requirePositiveWindow(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be a positive duration");
        }
    }

    public Duration window() {
        return Duration.ofMillis(windowMillis);
    }

    /**
     * Whether this policy applies to a request.
     *
     * <p>The organization must match, and the endpoint pattern must match when
     * one is set. An organization mismatch is a hard no: without it a policy
     * could be applied across tenants, which is the failure this whole class
     * exists to make impossible.
     */
    public boolean appliesTo(UUID requestOrganizationId, String requestEndpoint) {
        if (!organizationId.equals(requestOrganizationId)) {
            return false;
        }
        return endpointPattern == null || endpointPattern.isBlank()
                || matchesEndpoint(requestEndpoint);
    }

    /**
     * Exact match, or a trailing {@code *} prefix match.
     *
     * <p>Deliberately not a full glob or regex. A regex supplied by a developer
     * and evaluated per request is a denial-of-service risk, and the two forms
     * below cover the real cases without one.
     */
    private boolean matchesEndpoint(String requestEndpoint) {
        if (requestEndpoint == null) {
            return false;
        }
        if (endpointPattern.endsWith("*")) {
            return requestEndpoint.startsWith(endpointPattern.substring(0,
                    endpointPattern.length() - 1));
        }
        return endpointPattern.equals(requestEndpoint);
    }

    public boolean isTokenBucket() {
        return algorithm == RateLimitAlgorithm.TOKEN_BUCKET;
    }
}