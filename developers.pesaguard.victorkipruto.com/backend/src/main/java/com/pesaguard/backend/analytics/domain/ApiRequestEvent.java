package com.pesaguard.backend.analytics.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One inbound API request, recorded raw.
 *
 * <p>Raw events are the source of truth for every rollup. Rollups are always
 * <em>recomputed</em> from these rather than incremented, which is what makes
 * aggregation idempotent: running it twice produces the same answer, so a failure
 * halfway through can simply be re-run.
 *
 * <p>{@code requestId} is the natural idempotency key. A client retry, a proxy
 * replay, or a double-charged delivery all present the same request id, and the
 * unique constraint means a duplicate is rejected at the database rather than
 * silently double-counting a customer's usage.
 */
@Entity
@Table(name = "api_request_events")
public class ApiRequestEvent {

    @Id
    private UUID id;

    /**
     * Idempotency key. A retried or replayed request carries the same id and is
     * rejected here instead of inflating billable usage.
     */
    @Column(name = "request_id", nullable = false, unique = true, length = 64)
    private String requestId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    /** Null when the request came from an OAuth application rather than an API key. */
    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(name = "oauth_application_id")
    private UUID oauthApplicationId;

    @Column(name = "endpoint", nullable = false, length = 256)
    private String endpoint;

    @Column(name = "method", nullable = false, length = 8)
    private String method;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "response_bytes")
    private Long responseBytes;

    /**
     * When the request actually happened, not when it was recorded. A late event
     * still belongs in the window it occurred in, otherwise a backlog processed
     * after midnight would be counted against the wrong day.
     */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "user_id")
    private UUID userId;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected ApiRequestEvent() {
    }

    private ApiRequestEvent(String requestId, UUID organizationId, UUID projectId,
            UUID environmentId, UUID apiKeyId, UUID oauthApplicationId, String endpoint,
            String method, int statusCode, int latencyMs, Long responseBytes,
            Instant occurredAt, UUID userId) {
        this.id = UUID.randomUUID();
        this.requestId = requireRequestId(requestId);
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.apiKeyId = apiKeyId;
        this.oauthApplicationId = oauthApplicationId;
        this.endpoint = endpoint;
        this.method = method;
        this.statusCode = statusCode;
        this.latencyMs = Math.max(0, latencyMs);
        this.responseBytes = responseBytes == null || responseBytes < 0 ? null : responseBytes;
        this.occurredAt = occurredAt;
        this.userId = userId;
    }

/**
     * Request ids appear in log correlation and unique-index lookups, so a blank
     * or oversized one is rejected rather than stored.
     */
    private static String requireRequestId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("requestId must be at most 64 characters");
        }
        return trimmed;
    }

    public static ApiRequestEvent record(String requestId, UUID organizationId, UUID projectId,
            UUID environmentId, UUID apiKeyId, UUID oauthApplicationId, String endpoint,
            String method, int statusCode, int latencyMs, Long responseBytes,
            Instant occurredAt, UUID userId) {
        return new ApiRequestEvent(requestId, organizationId, projectId, environmentId,
                apiKeyId, oauthApplicationId, endpoint, method, statusCode, latencyMs,
                responseBytes, occurredAt, userId);
    }

    /** 2xx. */
    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    /** 4xx: the caller got something wrong. */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /** 5xx: PesaGuard got something wrong. */
    public boolean isServerError() {
        return statusCode >= 500;
    }

    /**
     * Anything that did not succeed.
     *
     * <p>1xx and 3xx are not failures: a 304 is a correct answer, and counting it
     * as an error would make the error rate meaningless.
     */
    public boolean isFailure() {
        return statusCode >= 400;
    }

    /** The credential used: exactly one of API key or OAuth application. */
    public boolean isAttributed() {
        return apiKeyId != null || oauthApplicationId != null;
    }

    public String getRequestId() { return requestId; }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public UUID getOAuthApplicationId() { return oauthApplicationId; }
    public String getEndpoint() { return endpoint; }
    public String getMethod() { return method; }
    public int getStatusCode() { return statusCode; }
    public int getLatencyMs() { return latencyMs; }
    public Long getResponseBytes() { return responseBytes; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getUserId() { return userId; }
    public Instant getRecordedAt() { return recordedAt; }
}
