package com.pesaguard.backend.ratelimit.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pesaguard.backend.common.api.SafeExceptionDiagnostics;
import com.pesaguard.backend.ratelimit.domain.RateLimitDecision;
import com.pesaguard.backend.ratelimit.domain.RateLimitPolicy;
import com.pesaguard.backend.ratelimit.domain.RateLimitScope;

/**
 * Evaluates a request against every limit that applies to it.
 *
 * <p>Scopes compose: a request is allowed only if <em>all</em> applicable limits
 * allow it. This is what lets a single runaway API key be stopped without
 * throttling the organization it belongs to — the key's own counter is exhausted
 * while the organization's barely moves.
 *
 * <p>The strictest decision wins, and the reported limit is that decision's. When
 * an endpoint limit of 10/min and an organization limit of 1000/min both apply,
 * the caller is told 10, because 10 is the number they have to act on.
 */
@Service
public class RateLimitService {

    public static final String RESPONSE_DECISION_ATTRIBUTE =
            RateLimitService.class.getName() + ".responseDecision";

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final RateLimitCounterStore store;

    public RateLimitService(RateLimitCounterStore store) {
        this.store = store;
    }

    /**
     * Consumes one unit from every applicable policy.
     *
     * <p>Evaluated most specific scope first, so the reported decision is
     * deterministic when several limits are exhausted at once.
     *
     * <p><b>Consumption is not rolled back.</b> If a later scope denies, the
     * earlier scopes have already been charged. That is intentional: the request
     * did consume their capacity, and refunding would let a caller exceed a limit
     * by making requests that are themselves refused.
     */
    public RateLimitDecision check(RateLimitRequest request, List<RateLimitPolicy> policies) {
        if (policies == null || policies.isEmpty()) {
            return RateLimitDecision.unlimited();
        }

        try {
            if (!store.isAvailable()) {
                throw new RateLimitUnavailableException();
            }

            long now = clockMillis(request);
            RateLimitDecision strictest = null;

            for (RateLimitPolicy policy : sortedBySpecificity(policies)) {
                if (!policy.appliesTo(request.organizationId(), request.endpoint())) {
                    continue;
                }
                RateLimitDecision decision = store.consume(policy, counterKey(policy, request), now);

                if (!decision.allowed()) {
                    return decision;
                }
                if (strictest == null || decision.remaining() < strictest.remaining()) {
                    strictest = decision;
                }
            }

            return strictest == null ? RateLimitDecision.unlimited() : strictest;
        } catch (RateLimitUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            log.error("rate limit evaluation failed; failing closed for organizationId={} type={} diagnostic={}",
                    request.organizationId(), failure.getClass().getName(),
                    SafeExceptionDiagnostics.stackTrace(failure));
            throw new RateLimitUnavailableException();
        }
    }

    /** Reads counters without consuming them, for the developer-facing view. */
    public RateLimitDecision peek(RateLimitRequest request, RateLimitPolicy policy) {
        try {
            if (!store.isAvailable()) {
                throw new RateLimitUnavailableException();
            }
            return store.peek(policy, counterKey(policy, request), clockMillis(request));
        } catch (RateLimitUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            log.error("rate limit preview failed organizationId={} type={} diagnostic={}",
                    request.organizationId(), failure.getClass().getName(),
                    SafeExceptionDiagnostics.stackTrace(failure));
            throw new RateLimitUnavailableException();
        }
    }

    private long clockMillis(RateLimitRequest request) {
        return request.evaluatedAt() == null ? System.currentTimeMillis()
                : request.evaluatedAt().toEpochMilli();
    }

    /**
     * Most specific scope first.
     *
     * <p>API key and endpoint before organization, so a caller is told the limit
     * they can actually act on rather than a global one they cannot change.
     */
    private List<RateLimitPolicy> sortedBySpecificity(List<RateLimitPolicy> policies) {
        return policies.stream()
                .sorted(java.util.Comparator.comparingInt(policy -> specificity(policy.scope())))
                .toList();
    }

    private int specificity(RateLimitScope scope) {
        return switch (scope) {
            case API_KEY -> 0;
            case WEBHOOK -> 1;
            case ENDPOINT -> 2;
            case USER -> 3;
            case IP -> 4;
            case PROJECT -> 5;
            case ENVIRONMENT -> 6;
            case ORGANIZATION -> 7;
        };
    }

    /**
     * The counter key.
     *
     * <p>Always namespaced by organization for tenant-scoped limits, and always
     * by policy. Two tenants whose subject ids collide must never share a
     * counter: that would let one exhaust another's limit.
     */
    String counterKey(RateLimitPolicy policy, RateLimitRequest request) {
        StringBuilder key = new StringBuilder("rl:")
                .append(policy.id())
                .append(':')
                .append(policy.scope());
        if (policy.scope().isTenantScoped() || policy.scope() == RateLimitScope.ORGANIZATION) {
            key.append(':').append(request.organizationId());
        }
        key.append(':').append(request.subjectKeyFor(policy.scope()));
        return key.toString();
    }

    /** The identity a request presents for each scope. */
    public record RateLimitRequest(
            UUID organizationId,
            UUID apiKeyId,
            UUID projectId,
            UUID environmentId,
            UUID userId,
            String endpoint,
            String ipAddress,
            UUID webhookId,
            Instant evaluatedAt) {

        public static RateLimitRequest of(UUID organizationId, UUID apiKeyId, UUID projectId,
                UUID environmentId, UUID userId, String endpoint, String ipAddress,
                UUID webhookId) {
            return new RateLimitRequest(organizationId, apiKeyId, projectId, environmentId,
                    userId, endpoint, ipAddress, webhookId, null);
        }

        /**
         * The key for a scope, or a placeholder when the request does not present
         * that identity.
         *
         * <p>Anonymous requests share a placeholder counter per policy. That is
         * intentional: otherwise an unauthenticated flood would mint a fresh counter
         * per request and never be limited at all.
         */
        public String subjectKeyFor(RateLimitScope scope) {
            UUID subject = switch (scope) {
                case API_KEY -> apiKeyId;
                case PROJECT -> projectId;
                case ENVIRONMENT -> environmentId;
                case USER -> userId;
                case WEBHOOK -> webhookId;
                case ORGANIZATION -> organizationId;
                case ENDPOINT, IP -> null;
            };
            if (subject != null) {
                return subject.toString();
            }
            return switch (scope) {
                case IP -> ipAddress == null ? "unknown" : ipAddress;
                case ENDPOINT -> endpoint == null ? "unknown" : endpoint;
                default -> "anonymous";
            };
        }
    }
}
