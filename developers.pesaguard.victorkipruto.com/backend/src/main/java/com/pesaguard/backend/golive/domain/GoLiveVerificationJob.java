package com.pesaguard.backend.golive.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "golive_verification_jobs", uniqueConstraints = @UniqueConstraint(
        name = "golive_verification_jobs_idempotency_unique",
        columnNames = {"organization_id", "project_id", "environment_id", "idempotency_key"}))
public class GoLiveVerificationJob {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "initiated_by", nullable = false)
    private UUID initiatedBy;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "verification_id")
    private UUID verificationId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GoLiveVerificationJob() {
    }

    private GoLiveVerificationJob(UUID organizationId, UUID projectId, UUID environmentId,
            UUID initiatedBy, String idempotencyKey, Instant queuedAt) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.initiatedBy = initiatedBy;
        this.idempotencyKey = idempotencyKey;
        this.status = "QUEUED";
        this.queuedAt = queuedAt;
    }

    public static GoLiveVerificationJob queue(UUID organizationId, UUID projectId, UUID environmentId,
            UUID initiatedBy, String idempotencyKey, Instant queuedAt) {
        return new GoLiveVerificationJob(organizationId, projectId, environmentId,
                initiatedBy, idempotencyKey, queuedAt);
    }

    public void start(Instant now) {
        if (!"QUEUED".equals(status)) {
            throw new IllegalStateException("Only a queued Go-Live verification can start.");
        }
        status = "RUNNING";
        startedAt = now;
    }

    public void complete(UUID completedVerificationId, Instant now) {
        if (!"RUNNING".equals(status)) {
            throw new IllegalStateException("Only a running Go-Live verification can complete.");
        }
        verificationId = completedVerificationId;
        status = "COMPLETED";
        completedAt = now;
    }

    public void fail(String reason, Instant now) {
        if (!"RUNNING".equals(status)) {
            throw new IllegalStateException("Only a running Go-Live verification can fail.");
        }
        failureReason = reason;
        status = "FAILED";
        completedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getStatus() { return status; }
    public UUID getVerificationId() { return verificationId; }
    public String getFailureReason() { return failureReason; }
    public Instant getQueuedAt() { return queuedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
