package com.pesaguard.backend.integration.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "integration_test_runs")
public class IntegrationTestRun {

    @Id
    private UUID id;

    @Column(name = "integration_id", nullable = false)
    private UUID integrationId;

    @Column(name = "test_type", nullable = false, length = 32)
    private String testType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private IntegrationTestStatus status;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "failure_category", length = 48)
    private String failureCategory;

    @Column(name = "safe_message", nullable = false, length = 500)
    private String safeMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IntegrationTestRun() {
    }

    private IntegrationTestRun(UUID integrationId, UUID requestId, Instant startedAt) {
        this.id = UUID.randomUUID();
        this.integrationId = integrationId;
        this.testType = "CONNECTION";
        this.status = IntegrationTestStatus.RUNNING;
        this.requestId = requestId;
        this.safeMessage = "Connection test is running.";
        this.startedAt = startedAt;
    }

    public static IntegrationTestRun start(UUID integrationId, UUID requestId, Instant startedAt) {
        return new IntegrationTestRun(integrationId, requestId, startedAt);
    }

    public void complete(boolean success, long latencyMs, String failureCategory,
            String safeMessage, Instant completedAt) {
        this.status = success ? IntegrationTestStatus.SUCCESS : IntegrationTestStatus.FAILED;
        this.latencyMs = Math.max(0, latencyMs);
        this.failureCategory = success ? null : failureCategory;
        this.safeMessage = safeMessage;
        this.completedAt = completedAt;
    }

    public UUID getId() { return id; }
    public UUID getIntegrationId() { return integrationId; }
    public String getTestType() { return testType; }
    public IntegrationTestStatus getStatus() { return status; }
    public UUID getRequestId() { return requestId; }
    public Long getLatencyMs() { return latencyMs; }
    public String getFailureCategory() { return failureCategory; }
    public String getSafeMessage() { return safeMessage; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
