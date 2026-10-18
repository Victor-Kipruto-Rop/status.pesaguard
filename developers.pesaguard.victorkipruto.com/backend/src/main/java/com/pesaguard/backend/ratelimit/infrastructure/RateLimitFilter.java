package com.pesaguard.backend.ratelimit.infrastructure;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.api.SafeExceptionDiagnostics;
import com.pesaguard.backend.ratelimit.application.RateLimitService;
import com.pesaguard.backend.ratelimit.application.RateLimitService.RateLimitRequest;
import com.pesaguard.backend.ratelimit.domain.RateLimitDecision;
import com.pesaguard.backend.ratelimit.domain.RateLimitPolicy;
import com.pesaguard.backend.ratelimit.domain.RateLimitScope;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Enforces rate limits on live requests.
 *
 * <p>This is the filter that makes the limiter real. The algorithms and the Redis
 * store were built and tested earlier; without a caller they meant nothing, and
 * no traffic was actually being limited.
 *
 * <p>Three properties are deliberate:
 *
 * <ul>
 *   <li><b>It fails closed.</b> If the counter store is unavailable, the request
 *       is rejected with a retryable 503 rather than bypassing the configured
 *       protection.</li>
 *   <li><b>It sets headers on every response</b>, not only on rejection, so an
 *       integrator can see remaining budget before they hit a wall.</li>
 *   <li><b>It charges before the work is done.</b> A rejected request still
 *       consumed budget, which is what stops a client hammering a limited
 *       endpoint from looking like low usage.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Baseline limit when nothing more specific is configured. */
    static final int DEFAULT_REQUESTS_PER_MINUTE = 600;

    static final String HEADER_LIMIT = "X-RateLimit-Limit";
    static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    static final String HEADER_RESET = "X-RateLimit-Reset";
    static final String HEADER_RETRY_AFTER = "Retry-After";

    /**
     * Organization used for requests with no tenant.
     *
     * <p>Counter keys always include the organization, so it must never be null.
     * Anonymous traffic is bucketed together rather than per-address, which is
     * the point: an unbounded anonymous bucket would leave flood protection
     * applying only to signed-in users.
     */
    private static final UUID ANONYMOUS_ORGANIZATION = stableId("pesaguard.anonymous");

    /** Stable so the policy id, and therefore the counter key, survives restarts. */
    private static final UUID BASELINE_POLICY_ID = stableId("pesaguard.baseline");

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private final ObjectProvider<RateLimitService> serviceProvider;
    private final Clock clock;

    public RateLimitFilter(ObjectProvider<RateLimitService> serviceProvider, Clock clock) {
        this.serviceProvider = serviceProvider;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        RateLimitService service = serviceProvider.getIfAvailable();
        if (service == null) {
            rejectLimiterUnavailable(request, response);
            return;
        }

        UUID organization = organizationFor();
        RateLimitDecision decision;
        try {
            decision = service.check(rateLimitRequest(request, organization),
                    List.of(baselinePolicyFor(organization)));
        } catch (RuntimeException limiterFailure) {
            log.error("Rate limiter unavailable requestId={} type={} diagnostic={}",
                    RequestContext.requestId(request), limiterFailure.getClass().getName(),
                    SafeExceptionDiagnostics.stackTrace(limiterFailure));
            rejectLimiterUnavailable(request, response);
            return;
        }

        applyHeaders(response, decision);

        if (!decision.allowed()) {
            // 429: SC_TOO_MANY_REQUESTS is not exposed on this Servlet API version.
            response.setStatus(429);
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":{\"code\":\"RATE_LIMITED\","
                            + "\"message\":\"You're sending requests too quickly. Please try again shortly.\","
                            + "\"requestId\":\"" + RequestContext.requestId(request) + "\","
                            + "\"timestamp\":\"" + Instant.now() + "\",\"violations\":[]}}");
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            Object environmentDecision = request.getAttribute(
                    RateLimitService.RESPONSE_DECISION_ATTRIBUTE);
            if (environmentDecision instanceof RateLimitDecision appliedEnvironmentLimit) {
                // The resource-specific policy is stricter and more actionable than
                // the general IP bucket, so it owns the final response headers.
                applyHeaders(response, appliedEnvironmentLimit);
            }
        }
    }

    private static void rejectLimiterUnavailable(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader(HEADER_RETRY_AFTER, "5");
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":{\"code\":\"SERVICE_UNAVAILABLE\","
                        + "\"message\":\"This service is temporarily unavailable. Please try again shortly.\","
                        + "\"requestId\":\"" + RequestContext.requestId(request) + "\","
                        + "\"timestamp\":\"" + Instant.now() + "\",\"violations\":[]}}");
    }

    /**
     * The baseline policy for one organization.
     *
     * <p>Built per request rather than held as a field: a policy carries the
     * organization it applies to, so a single shared instance would never match a
     * real tenant and nothing would ever be limited.
     */
    private RateLimitPolicy baselinePolicyFor(UUID organizationId) {
        return RateLimitPolicy.tokenBucket(BASELINE_POLICY_ID, organizationId,
                RateLimitScope.IP, null, null,
                DEFAULT_REQUESTS_PER_MINUTE, Duration.ofMinutes(1), null);
    }

    /**
     * Publishes limit state on every response, not only on rejection.
     *
     * <p>An integrator that can see remaining budget can back off before being
     * refused, which is the difference between a throttle that works and one that
     * just fails.
     */
    private void applyHeaders(HttpServletResponse response, RateLimitDecision decision) {
        if (decision.isUnlimited()) {
            return;
        }
        response.setHeader(HEADER_LIMIT, String.valueOf(decision.limit()));
        response.setHeader(HEADER_REMAINING, String.valueOf(decision.remaining()));
        response.setHeader(HEADER_RESET,
                String.valueOf(decision.resetAfterSeconds(clock.instant())));
        if (!decision.allowed()) {
            response.setHeader(HEADER_RETRY_AFTER,
                    String.valueOf(Math.max(1L, decision.retryAfterSeconds())));
        }
    }

    private UUID organizationFor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser principal
                && principal.organizationId() != null) {
            return principal.organizationId();
        }
        return ANONYMOUS_ORGANIZATION;
    }

    private RateLimitRequest rateLimitRequest(HttpServletRequest request, UUID organization) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID user = null;
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser principal) {
            user = principal.userId();
        }
        return new RateLimitRequest(organization, null, null, null, user,
                safePath(request), request.getRemoteAddr(), null, clock.instant());
    }

    /**
     * Bounded path.
     *
     * <p>The endpoint is part of a counter key and, for endpoint-scoped limits, of
     * a bucket count. An unbounded path taken from a hostile client would be a
     * memory-growth vector.
     */
    private String safePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            return "/";
        }
        return uri.length() <= 128 ? uri : uri.substring(0, 128);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Probes must never be throttled: doing so turns a load balancer's health
        // check into a false negative and takes the pod out of service.
        return path != null && (path.startsWith("/actuator") || path.startsWith("/health")
                || path.equals("/internal/health"));
    }
}
