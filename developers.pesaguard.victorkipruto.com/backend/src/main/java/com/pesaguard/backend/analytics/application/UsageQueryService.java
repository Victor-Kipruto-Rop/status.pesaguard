package com.pesaguard.backend.analytics.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.analytics.domain.UsageBucket;
import com.pesaguard.backend.analytics.domain.UsageGranularity;
import com.pesaguard.backend.analytics.infrastructure.UsageBucketRepository;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.UnauthorizedException;
import com.pesaguard.backend.project.application.ProjectAccessScope;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Read side of usage analytics.
 *
 * <p><b>Every query here is scoped to the caller's organization.</b> The
 * organization comes from the authenticated principal and is never read from a
 * request parameter, so there is no way to ask for another tenant's usage by
 * changing a query string. Usage is customer-facing and often feeds a bill, so
 * cross-tenant leakage here would be both a breach and a financial incident.
 */
@Service
public class UsageQueryService {

    /** Longest span a single request may ask for. */
    static final Duration MAX_SPAN = Duration.ofDays(366);

    private final UsageBucketRepository bucketRepository;
    private final Clock clock;
    private final ProjectAccessScope projectAccessScope;
    private final AuthorizationService authorizationService;
    private final ApiKeyRepository apiKeyRepository;

    public UsageQueryService(UsageBucketRepository bucketRepository, Clock clock,
            ProjectAccessScope projectAccessScope, AuthorizationService authorizationService,
            ApiKeyRepository apiKeyRepository) {
        this.bucketRepository = bucketRepository;
        this.clock = clock;
        this.projectAccessScope = projectAccessScope;
        this.authorizationService = authorizationService;
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * Time series for the caller's organization.
     *
     * <p>Returns the granularity actually used, which may be coarser than asked
     * for. That is reported rather than hidden, because a caller comparing
     * "hourly" numbers against a "daily" total needs to know which they got.
     */
    @Transactional(readOnly = true)
    public UsageSeries series(AuthenticatedUser principal, Instant from, Instant to,
            UsageGranularity requested, UUID projectId, UUID environmentId) {
        return series(principal, from, to, requested, projectId, environmentId, null);
    }

    @Transactional(readOnly = true)
    public UsageSeries series(AuthenticatedUser principal, Instant from, Instant to,
            UsageGranularity requested, UUID projectId, UUID environmentId, UUID apiKeyId) {
        requireUsageRead(principal);
        requireKeyScope(principal, projectId, environmentId, apiKeyId);
        Instant windowFrom = from == null ? clock.instant().minus(Duration.ofHours(24)) : from;
        Instant windowTo = to == null ? clock.instant() : to;
        Duration span = Duration.between(windowFrom, windowTo);

        if (span.isNegative() || span.isZero()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "USAGE_RANGE_INVALID",
                    "The end of the range must be after the start.");
        }
        if (span.compareTo(MAX_SPAN) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "USAGE_RANGE_TOO_LARGE",
                    "The range may not exceed " + MAX_SPAN.toDays() + " days.");
        }

        UsageGranularity granularity = requested != null
                ? requested
                : UsageAggregationService.recommendedFor(span);

        ProjectAccessScope.Scope scope = projectAccessScope.resolve(principal, projectId, environmentId);
        if (scope.isEmpty()) {
            return new UsageSeries(granularity, windowFrom, windowTo, UsageSummary.from(List.of()), List.of());
        }
        List<UsageBucket> buckets = scope.projectIds() == null
                ? bucketRepository.findForOrganization(principal.organizationId(), granularity, windowFrom, windowTo)
                : bucketRepository.findForOrganizationAndProjects(principal.organizationId(), scope.projectIds(),
                        granularity, windowFrom, windowTo);

        List<UsageBucket> filteredBuckets = filterByDimensions(
                buckets, scope.projectId(), scope.environmentId(), apiKeyId);
        return new UsageSeries(granularity, windowFrom, windowTo,
                UsageSummary.from(filteredBuckets), filteredBuckets.stream().map(UsagePoint::from).toList());
    }

    /**
     * Bounded, tenant and project scoped usage rows for CSV export. Pagination is
     * pushed into the repository so a large export cannot materialize every
     * usage dimension in application memory.
     */
    @Transactional(readOnly = true)
    public Page<UsageBucket> exportRows(AuthenticatedUser principal, Instant from, Instant to,
            UsageGranularity requested, UUID projectId, UUID environmentId, Pageable pageable) {
        return exportRows(principal, from, to, requested, projectId, environmentId, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<UsageBucket> exportRows(AuthenticatedUser principal, Instant from, Instant to,
            UsageGranularity requested, UUID projectId, UUID environmentId, UUID apiKeyId,
            Pageable pageable) {
        requireUsageRead(principal);
        requireKeyScope(principal, projectId, environmentId, apiKeyId);
        Instant windowFrom = from == null ? clock.instant().minus(Duration.ofHours(24)) : from;
        Instant windowTo = to == null ? clock.instant() : to;
        Duration span = Duration.between(windowFrom, windowTo);
        if (span.isNegative() || span.isZero()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "USAGE_RANGE_INVALID",
                    "The end of the range must be after the start.");
        }
        if (span.compareTo(MAX_SPAN) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "USAGE_RANGE_TOO_LARGE",
                    "The range may not exceed " + MAX_SPAN.toDays() + " days.");
        }

        UsageGranularity granularity = requested != null
                ? requested
                : UsageAggregationService.recommendedFor(span);
        ProjectAccessScope.Scope scope = projectAccessScope.resolve(principal, projectId, environmentId);
        if (scope.isEmpty()) {
            return Page.empty(pageable);
        }
        if (scope.projectIds() == null) {
            return bucketRepository.findExportRowsForOrganization(principal.organizationId(),
                    granularity, windowFrom, windowTo, scope.projectId(), scope.environmentId(), apiKeyId, pageable);
        }
        return bucketRepository.findExportRowsForProjects(principal.organizationId(),
                scope.projectIds(), granularity, windowFrom, windowTo, scope.projectId(),
                scope.environmentId(), apiKeyId, pageable);
    }

    /**
     * Applies optional dimension filters.
     *
     * <p>Filters <b>after</b> the tenant query, never inside it. The organization
     * predicate is applied by the repository and cannot be influenced by these
     * parameters, so a caller cannot use them to escape their own data.
     */
    private List<UsageBucket> filterByDimensions(List<UsageBucket> buckets,
            UUID projectId, UUID environmentId, UUID apiKeyId) {
        return buckets.stream()
                .filter(bucket -> projectId == null || projectId.equals(bucket.getProjectId()))
                .filter(bucket -> environmentId == null
                        || environmentId.equals(bucket.getEnvironmentId()))
                .filter(bucket -> apiKeyId == null || apiKeyId.equals(bucket.getApiKeyId()))
                .toList();
    }

    /** Endpoints used in a window, for a usage breakdown. */
    @Transactional(readOnly = true)
    public List<String> endpoints(AuthenticatedUser principal, Instant from, Instant to,
            UsageGranularity granularity, UUID projectId, UUID environmentId) {
        return endpoints(principal, from, to, granularity, projectId, environmentId, null);
    }

    @Transactional(readOnly = true)
    public List<String> endpoints(AuthenticatedUser principal, Instant from, Instant to,
            UsageGranularity granularity, UUID projectId, UUID environmentId, UUID apiKeyId) {
        requireUsageRead(principal);
        requireKeyScope(principal, projectId, environmentId, apiKeyId);
        Instant windowFrom = from == null ? clock.instant().minus(Duration.ofHours(24)) : from;
        Instant windowTo = to == null ? clock.instant() : to;
        ProjectAccessScope.Scope scope = projectAccessScope.resolve(principal, projectId, environmentId);
        if (scope.isEmpty()) return List.of();
        if (apiKeyId != null) {
            List<UsageBucket> buckets = scope.projectIds() == null
                    ? bucketRepository.findForOrganization(principal.organizationId(), granularity,
                            windowFrom, windowTo)
                    : bucketRepository.findForOrganizationAndProjects(principal.organizationId(),
                            scope.projectIds(), granularity, windowFrom, windowTo);
            return buckets.stream()
                    .filter(bucket -> apiKeyId.equals(bucket.getApiKeyId()))
                    .filter(bucket -> scope.projectId() == null || scope.projectId().equals(bucket.getProjectId()))
                    .filter(bucket -> scope.environmentId() == null || scope.environmentId().equals(bucket.getEnvironmentId()))
                    .map(UsageBucket::getEndpoint)
                    .distinct()
                    .sorted()
                    .toList();
        }
        return scope.projectIds() == null
                ? bucketRepository.findDistinctEndpoints(principal.organizationId(),
                        granularity, windowFrom, windowTo)
                : bucketRepository.findDistinctEndpointsForProjects(principal.organizationId(), scope.projectIds(),
                        scope.environmentId(), granularity, windowFrom, windowTo);
    }

    private void requireKeyScope(AuthenticatedUser principal, UUID projectId,
            UUID environmentId, UUID apiKeyId) {
        if (apiKeyId == null) return;
        if (projectId == null || environmentId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "USAGE_KEY_SCOPE_REQUIRED",
                    "Project and environment are required when filtering usage by API key.");
        }
        authorizationService.requirePermission(principal, Permission.CREDENTIAL_READ);
        if (!apiKeyRepository.existsByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                apiKeyId, principal.organizationId(), projectId, environmentId)) {
            throw new ResourceNotFoundException("API key");
        }
    }

    /**
     * Verifies the caller may read usage.
     *
     * <p>Explicit rather than assumed from authentication: being logged in is not
     * permission to read a customer's usage and billing-shaped data.
     */
    private void requireUsageRead(AuthenticatedUser principal) {
        if (principal == null) {
            throw new UnauthorizedException("USAGE_UNAUTHENTICATED",
                    "Authentication is required.");
        }
        if (!authorizationService.hasPermission(principal, Permission.USAGE_READ)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "USAGE_FORBIDDEN",
                    "You do not have permission to read usage.");
        }
    }

    /** Aggregate totals across a set of buckets. */
    public record UsageSummary(
            long totalRequests,
            long successfulRequests,
            long failedRequests,
            long clientErrors,
            long serverErrors,
            double errorRate,
            double averageLatencyMs,
            long p95LatencyMs,
            Long responseBytes) {

        static UsageSummary from(List<UsageBucket> buckets) {
            long total = 0;
            long successful = 0;
            long failed = 0;
            long clientErrors = 0;
            long serverErrors = 0;
            long latencySum = 0;
            long bytes = 0;
            boolean anyBytes = false;
            long p95 = 0;

            for (UsageBucket bucket : buckets) {
                total += bucket.getTotalRequests();
                successful += bucket.getSuccessfulRequests();
                failed += bucket.getFailedRequests();
                clientErrors += bucket.getClientErrors();
                serverErrors += bucket.getServerErrors();
                latencySum += bucket.getLatencySumMs();
                p95 = Math.max(p95, bucket.getP95LatencyMs());
                if (bucket.getResponseBytesTotal() != null) {
                    anyBytes = true;
                    bytes += bucket.getResponseBytesTotal();
                }
            }

            double errorRate = total <= 0 ? 0.0d : (double) failed / (double) total;
            double average = total <= 0 ? 0.0d : (double) latencySum / (double) total;
            return new UsageSummary(total, successful, failed, clientErrors, serverErrors,
                    errorRate, average, p95, anyBytes ? bytes : null);
        }
    }

    /** One point in a time series. */
    public record UsagePoint(
            Instant windowStart,
            long totalRequests,
            long successfulRequests,
            long failedRequests,
            double errorRate,
            long p50LatencyMs,
            long p95LatencyMs,
            long p99LatencyMs) {

        static UsagePoint from(UsageBucket bucket) {
            return new UsagePoint(bucket.getWindowStart(), bucket.getTotalRequests(),
                    bucket.getSuccessfulRequests(), bucket.getFailedRequests(),
                    bucket.errorRate(), bucket.getP50LatencyMs(), bucket.getP95LatencyMs(),
                    bucket.getP99LatencyMs());
        }
    }

    /** A series plus the totals behind it. */
    public record UsageSeries(
            UsageGranularity granularity,
            Instant from,
            Instant to,
            UsageSummary summary,
            List<UsagePoint> points) {
    }
}
