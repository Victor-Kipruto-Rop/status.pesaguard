package com.pesaguard.backend.loadtest.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "load_test_runs")
public class LoadTestRun {
    @Id
    private UUID id;

    @Column(name = "load_test_id", nullable = false)
    private UUID loadTestId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private LoadTestLifecycle status;

    @Column(name = "availability_reason", length = 100)
    private String availabilityReason;

    @Column(name = "requested_vus", nullable = false)
    private int requestedVus;

    @Column(name = "allocated_vus", nullable = false)
    private int allocatedVus;

    @Column(name = "requested_rps")
    private Integer requestedRps;

    @Column(name = "estimated_duration_seconds")
    private Long estimatedDurationSeconds;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "configuration_snapshot", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> configurationSnapshot;

    @Column(name = "result_summary", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> resultSummary;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LoadTestRun() {
    }

    public static LoadTestRun queued(LoadTestConfiguration configuration, UUID actorId,
            Map<String, Object> snapshot, long estimatedDurationSeconds) {
        LoadTestRun run = new LoadTestRun();
        run.id = UUID.randomUUID();
        run.loadTestId = configuration.getId();
        run.organizationId = configuration.getOrganizationId();
        run.projectId = configuration.getProjectId();
        run.environmentId = configuration.getEnvironmentId();
        run.createdBy = actorId;
        run.status = LoadTestLifecycle.QUEUED;
        run.availabilityReason = "NO_HEALTHY_GENERATORS";
        run.requestedVus = configuration.getTargetVus();
        run.requestedRps = configuration.getTargetRps();
        run.allocatedVus = 0;
        run.estimatedDurationSeconds = estimatedDurationSeconds;
        run.configurationSnapshot = Map.copyOf(snapshot);
        return run;
    }

    public void queueReason(String reason) { availabilityReason = reason; }

    public void markPreparing(Instant now) {
        if (status != LoadTestLifecycle.QUEUED) throw new IllegalStateException("Run is not queued");
        status = LoadTestLifecycle.PREPARING;
        availabilityReason = null;
        if (startedAt == null) startedAt = now;
    }

    public void markRunning(Instant now) {
        if (status != LoadTestLifecycle.PREPARING && status != LoadTestLifecycle.QUEUED) {
            throw new IllegalStateException("Run cannot enter RUNNING from " + status);
        }
        status = LoadTestLifecycle.RUNNING;
        availabilityReason = null;
        if (startedAt == null) startedAt = now;
    }

    public void stop() {
        if (status == LoadTestLifecycle.QUEUED) {
            status = LoadTestLifecycle.CANCELLED;
            finishedAt = Instant.now();
        } else if (status == LoadTestLifecycle.PREPARING || status == LoadTestLifecycle.RUNNING
                || status == LoadTestLifecycle.PAUSED) {
            status = LoadTestLifecycle.STOPPING;
        } else {
            throw new IllegalStateException("Run cannot be stopped from " + status);
        }
    }

    public void cancel() {
        if (status != LoadTestLifecycle.QUEUED) throw new IllegalStateException("Only queued runs can be cancelled");
        status = LoadTestLifecycle.CANCELLED;
        finishedAt = Instant.now();
        availabilityReason = null;
    }

    public void complete(LoadTestLifecycle terminalStatus, Map<String, Object> summary,
            String failureReason, Instant now) {
        if (status != LoadTestLifecycle.RUNNING && status != LoadTestLifecycle.STOPPING
                && status != LoadTestLifecycle.PREPARING) {
            throw new IllegalStateException("Run cannot complete from " + status);
        }
        if (terminalStatus != LoadTestLifecycle.COMPLETED && terminalStatus != LoadTestLifecycle.FAILED
                && terminalStatus != LoadTestLifecycle.CANCELLED && terminalStatus != LoadTestLifecycle.TIMEOUT) {
            throw new IllegalArgumentException("A terminal run status is required");
        }
        status = terminalStatus;
        resultSummary = summary == null ? null : Map.copyOf(summary);
        this.failureReason = failureReason;
        finishedAt = now;
        availabilityReason = null;
    }

    public void setAllocation(int vus) { allocatedVus = vus; }

    public UUID getId() { return id; }
    public UUID getLoadTestId() { return loadTestId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public UUID getCreatedBy() { return createdBy; }
    public LoadTestLifecycle getStatus() { return status; }
    public String getAvailabilityReason() { return availabilityReason; }
    public int getRequestedVus() { return requestedVus; }
    public int getAllocatedVus() { return allocatedVus; }
    public Integer getRequestedRps() { return requestedRps; }
    public Long getEstimatedDurationSeconds() { return estimatedDurationSeconds; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Map<String, Object> getConfigurationSnapshot() { return configurationSnapshot; }
    public Map<String, Object> getResultSummary() { return resultSummary; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
}
