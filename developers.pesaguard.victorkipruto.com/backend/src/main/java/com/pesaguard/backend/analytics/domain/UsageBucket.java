package com.pesaguard.backend.analytics.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A pre-aggregated usage bucket for one window and one set of dimensions.
 *
 * <p>The critical property is that {@link #replaceAggregates} sets <b>absolute</b>
 * values rather than adding to them. Aggregation is therefore idempotent: running
 * it twice over the same raw events produces identical numbers. That is what makes
 * recovery from a mid-rollup failure trivial — re-run it.
 *
 * <p>Incremental accumulation would not be idempotent, and re-running it to recover
 * from a failure would double every total.
 *
 * <p>{@code lateEventCount} records that an event arrived after this window had
 * already been rolled up, so an operator can tell "this bucket is complete" from
 * "this bucket was completed and then adjusted".
 */
@Entity
@Table(name = "usage_buckets")
public class UsageBucket {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "granularity", nullable = false, length = 16)
    private UsageGranularity granularity;

    /** Inclusive start of the window, always a truncated boundary in UTC. */
    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    /** Null when aggregating over all credentials, for an environment-level rollup. */
    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(name = "oauth_application_id")
    private UUID oauthApplicationId;

    @Column(name = "endpoint", nullable = false, length = 256)
    private String endpoint;

    @Column(name = "method", nullable = false, length = 8)
    private String method;

    @Column(name = "total_requests", nullable = false)
    private long totalRequests;

    @Column(name = "successful_requests", nullable = false)
    private long successfulRequests;

    @Column(name = "failed_requests", nullable = false)
    private long failedRequests;

    @Column(name = "client_errors", nullable = false)
    private long clientErrors;

    @Column(name = "server_errors", nullable = false)
    private long serverErrors;

    @Column(name = "latency_sum_ms", nullable = false)
    private long latencySumMs;

    @Column(name = "latency_max_ms", nullable = false)
    private long latencyMaxMs;

    @Column(name = "p50_latency_ms", nullable = false)
    private long p50LatencyMs;

    @Column(name = "p95_latency_ms", nullable = false)
    private long p95LatencyMs;

    @Column(name = "p99_latency_ms", nullable = false)
    private long p99LatencyMs;

    @Column(name = "response_bytes_total")
    private Long responseBytesTotal;

    /** Events that arrived after this window was first rolled up. */
    @Column(name = "late_event_count", nullable = false)
    private long lateEventCount;

    /** Latest {@code occurredAt} folded in, so a re-run can detect new arrivals. */
    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Version
    @Column(name = "version_lock", nullable = false)
    private long versionLock;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UsageBucket() {
    }

    private UsageBucket(UsageGranularity granularity, Instant windowStart, UUID organizationId,
            UUID projectId, UUID environmentId, UUID apiKeyId, UUID oauthApplicationId,
            String endpoint, String method) {
        this.id = UUID.randomUUID();
        this.granularity = granularity;
        this.windowStart = granularity.windowStart(windowStart);
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.apiKeyId = apiKeyId;
        this.oauthApplicationId = oauthApplicationId;
        this.endpoint = endpoint;
        this.method = method;
    }

    public static UsageBucket forWindow(UsageGranularity granularity, Instant windowStart,
            UUID organizationId, UUID projectId, UUID environmentId, UUID apiKeyId,
            UUID oauthApplicationId, String endpoint, String method) {
        return new UsageBucket(granularity, windowStart, organizationId, projectId,
                environmentId, apiKeyId, oauthApplicationId, endpoint, method);
    }

/**
 * Replaces every aggregate with a freshly computed set.
 *
 * <p>Absolute, not additive. This is the property that makes aggregation safe
 * to retry: a rollup that ran halfway through and failed can simply be run
 * again, and the totals will be right. An additive counter would double every
 * figure on the second run.
 *
 * @param lateEvents how many of the contributing events arrived after this
 *        bucket was first closed; zero on a normal run
 */
    public void replaceAggregates(Aggregates aggregates, int lateEvents) {
        this.totalRequests = aggregates.total();
        this.successfulRequests = aggregates.successful();
        this.failedRequests = aggregates.failed();
        this.clientErrors = aggregates.clientErrors();
        this.serverErrors = aggregates.serverErrors();
        this.latencySumMs = aggregates.latencySum();
        this.latencyMaxMs = aggregates.latencyMax();
        this.p50LatencyMs = aggregates.p50();
        this.p95LatencyMs = aggregates.p95();
        this.p99LatencyMs = aggregates.p99();
        this.responseBytesTotal = aggregates.responseBytes();
        this.lastEventAt = aggregates.latestEventAt();
        if (lateEvents > 0) {
            this.lateEventCount += lateEvents;
        }
    }

    /**
     * Failure rate as a fraction, not a percentage.
     *
     * <p>Returns {@code 0.0} for an empty bucket rather than dividing by zero.
     * An empty window is not a 0% error rate in any meaningful sense, but returning
     * NaN here would propagate into dashboards as a broken tile, and an operator
     * cannot tell a real 0% from a broken one.
     */
    public double errorRate() {
        if (totalRequests <= 0) {
            return 0.0d;
        }
        return (double) failedRequests / (double) totalRequests;
    }

    /** Mean latency, or 0 for an empty bucket. */
    public double averageLatencyMs() {
        if (totalRequests <= 0) {
            return 0.0d;
        }
        return (double) latencySumMs / (double) totalRequests;
    }

    /** The immutable result of aggregating a set of raw events. */
    public record Aggregates(
            long total,
            long successful,
            long failed,
            long clientErrors,
            long serverErrors,
            long latencySum,
            long latencyMax,
            long p50,
            long p95,
            long p99,
            Long responseBytes,
            Instant latestEventAt) {
    }

    public UUID getId() { return id; }
    public UsageGranularity getGranularity() { return granularity; }
    public Instant getWindowStart() { return windowStart; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public UUID getOAuthApplicationId() { return oauthApplicationId; }
    public String getEndpoint() { return endpoint; }
    public String getMethod() { return method; }
    public long getTotalRequests() { return totalRequests; }
    public long getSuccessfulRequests() { return successfulRequests; }
    public long getFailedRequests() { return failedRequests; }
    public long getClientErrors() { return clientErrors; }
    public long getServerErrors() { return serverErrors; }
    public long getLatencySumMs() { return latencySumMs; }
    public long getLatencyMaxMs() { return latencyMaxMs; }
    public long getP50LatencyMs() { return p50LatencyMs; }
    public long getP95LatencyMs() { return p95LatencyMs; }
    public long getP99LatencyMs() { return p99LatencyMs; }
    public Long getResponseBytesTotal() { return responseBytesTotal; }
    public long getLateEventCount() { return lateEventCount; }
    public Instant getLastEventAt() { return lastEventAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
