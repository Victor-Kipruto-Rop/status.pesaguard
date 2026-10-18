package com.pesaguard.backend.sandbox.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-sandbox quotas.
 *
 * <p>Deliberately separate from {@code EnvironmentLimits}. A sandbox has its own
 * budget so that a developer hammering the sandbox with test traffic exhausts the
 * sandbox allowance and can never consume the environment's production budget.
 * Sharing a limit row would mean a noisy integration test could throttle real
 * customers.
 *
 * <p>All values must be positive. Zero is not "no limit" here: it would mean the
 * sandbox cannot do anything, and it is far more likely to be a units mistake
 * than an intent. A sandbox that genuinely must be unconstrained should be
 * deleted, not given a zero budget.
 */
@Entity
@Table(name = "sandbox_limits")
public class SandboxLimits {

    /** Ceiling on a single sandbox execution. Mirrored by a check constraint. */
    public static final int MAX_EXECUTION_TIMEOUT_MS = 30_000;

    @Id
    @Column(name = "sandbox_id", nullable = false)
    private UUID sandboxId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "requests_per_minute", nullable = false)
    private int requestsPerMinute;

    @Column(name = "burst_requests", nullable = false)
    private int burstRequests;

    @Column(name = "max_api_keys", nullable = false)
    private int maxApiKeys;

    @Column(name = "max_credentials", nullable = false)
    private int maxCredentials;

    @Column(name = "max_webhook_endpoints", nullable = false)
    private int maxWebhookEndpoints;

    @Column(name = "max_events_per_minute", nullable = false)
    private int maxEventsPerMinute;

    @Column(name = "max_request_body_bytes", nullable = false)
    private int maxRequestBodyBytes;

    @Column(name = "max_response_body_bytes", nullable = false)
    private int maxResponseBodyBytes;

    @Column(name = "execution_timeout_ms", nullable = false)
    private int executionTimeoutMs;

    @Column(name = "max_history_entries", nullable = false)
    private int maxHistoryEntries;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SandboxLimits() {
    }

    private SandboxLimits(UUID sandboxId, UUID organizationId) {
        this.sandboxId = sandboxId;
        this.organizationId = organizationId;
        this.requestsPerMinute = 120;
        this.burstRequests = 20;
        this.maxApiKeys = 3;
        this.maxCredentials = 10;
        this.maxWebhookEndpoints = 3;
        this.maxEventsPerMinute = 60;
        this.maxRequestBodyBytes = 64 * 1024;
        this.maxResponseBodyBytes = 64 * 1024;
        this.executionTimeoutMs = 5_000;
        this.maxHistoryEntries = 200;
    }

    public static SandboxLimits defaults(UUID sandboxId, UUID organizationId) {
        return new SandboxLimits(sandboxId, organizationId);
    }
public void update(int requestsPerMinute, int burstRequests, int maxApiKeys, int maxCredentials,
            int maxWebhookEndpoints, int maxEventsPerMinute, int maxRequestBodyBytes,
            int maxResponseBodyBytes, int executionTimeoutMs, int maxHistoryEntries,
            UUID updatedByUser) {
        requirePositive(requestsPerMinute, "requestsPerMinute");
        requirePositive(burstRequests, "burstRequests");
        requirePositive(maxApiKeys, "maxApiKeys");
        requirePositive(maxCredentials, "maxCredentials");
        requirePositive(maxWebhookEndpoints, "maxWebhookEndpoints");
        requirePositive(maxEventsPerMinute, "maxEventsPerMinute");
        requirePositive(maxRequestBodyBytes, "maxRequestBodyBytes");
        requirePositive(maxResponseBodyBytes, "maxResponseBodyBytes");
        requirePositive(maxHistoryEntries, "maxHistoryEntries");
        if (executionTimeoutMs <= 0 || executionTimeoutMs > MAX_EXECUTION_TIMEOUT_MS) {
            // A sandbox timeout longer than this would let one hung call hold a
            // thread longer than an operator would tolerate before noticing.
            throw new IllegalArgumentException("executionTimeoutMs must be between 1 and "
                    + MAX_EXECUTION_TIMEOUT_MS);
        }
        this.requestsPerMinute = requestsPerMinute;
        this.burstRequests = burstRequests;
        this.maxApiKeys = maxApiKeys;
        this.maxCredentials = maxCredentials;
        this.maxWebhookEndpoints = maxWebhookEndpoints;
        this.maxEventsPerMinute = maxEventsPerMinute;
        this.maxRequestBodyBytes = maxRequestBodyBytes;
        this.maxResponseBodyBytes = maxResponseBodyBytes;
        this.executionTimeoutMs = executionTimeoutMs;
        this.maxHistoryEntries = maxHistoryEntries;
        this.updatedBy = updatedByUser;
    }

    /**
     * The timeout an execution may actually use: never more than the sandbox's own
     * ceiling, however long the caller asked for.
     */
    public int effectiveTimeoutMs(int requestedMs) {
        return Math.min(Math.max(1, requestedMs), executionTimeoutMs);
    }

    /** Refuses a payload larger than the sandbox permits, before it is buffered. */
    public void requireRequestWithinLimit(int bodyBytes) {
        if (bodyBytes > maxRequestBodyBytes) {
            throw new IllegalArgumentException("Sandbox request body exceeds the "
                    + maxRequestBodyBytes + " byte limit");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public UUID getSandboxId() { return sandboxId; }
    public UUID getOrganizationId() { return organizationId; }
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public int getBurstRequests() { return burstRequests; }
    public int getMaxApiKeys() { return maxApiKeys; }
    public int getMaxCredentials() { return maxCredentials; }
    public int getMaxWebhookEndpoints() { return maxWebhookEndpoints; }
    public int getMaxEventsPerMinute() { return maxEventsPerMinute; }
    public int getMaxRequestBodyBytes() { return maxRequestBodyBytes; }
    public int getMaxResponseBodyBytes() { return maxResponseBodyBytes; }
    public int getExecutionTimeoutMs() { return executionTimeoutMs; }
    public int getMaxHistoryEntries() { return maxHistoryEntries; }
    public UUID getUpdatedBy() { return updatedBy; }
}
