package com.pesaguard.backend.golive.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "golive_launches")
public class GoLiveLaunch {

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

    @Column(name = "verification_id", nullable = false)
    private UUID verificationId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "request_id", length = 80)
    private String requestId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GoLiveLaunch() {
    }

    private GoLiveLaunch(UUID organizationId, UUID projectId, UUID environmentId,
            UUID initiatedBy, UUID verificationId, String idempotencyKey,
            String status, String failureReason, Instant startedAt, Instant completedAt, String requestId) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.initiatedBy = initiatedBy;
        this.verificationId = verificationId;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.failureReason = failureReason;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.requestId = requestId;
    }

    public static GoLiveLaunch completed(UUID organizationId, UUID projectId, UUID environmentId,
            UUID initiatedBy, UUID verificationId, String idempotencyKey,
            Instant startedAt, Instant completedAt, String requestId) {
        return new GoLiveLaunch(organizationId, projectId, environmentId, initiatedBy,
                verificationId, idempotencyKey, "LIVE", null, startedAt, completedAt, requestId);
    }

    public static GoLiveLaunch failed(UUID organizationId, UUID projectId, UUID environmentId,
            UUID initiatedBy, UUID verificationId, String idempotencyKey,
            String failureReason, Instant startedAt, Instant completedAt, String requestId) {
        return new GoLiveLaunch(organizationId, projectId, environmentId, initiatedBy,
                verificationId, idempotencyKey, "LAUNCH_FAILED", failureReason,
                startedAt, completedAt, requestId);
    }

    public void revoke(Instant now) {
        if (!"LIVE".equals(status)) {
            throw new IllegalStateException("Only a live Go-Live launch can be revoked.");
        }
        status = "REVOKED";
        completedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public UUID getVerificationId() { return verificationId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getRequestId() { return requestId; }
}
