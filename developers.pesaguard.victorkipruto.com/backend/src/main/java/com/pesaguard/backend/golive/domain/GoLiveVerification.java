package com.pesaguard.backend.golive.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.pesaguard.backend.golive.api.GoLiveCheckView;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "golive_verifications", uniqueConstraints = @UniqueConstraint(
        name = "golive_verifications_idempotency_unique",
        columnNames = {"organization_id", "project_id", "environment_id", "idempotency_key"}))
public class GoLiveVerification {

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

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "state", nullable = false, length = 24)
    private String state;

    @Column(name = "readiness_percent", nullable = false)
    private int readinessPercent;

    @Column(name = "passed_checks", nullable = false)
    private int passedChecks;

    @Column(name = "failed_checks", nullable = false)
    private int failedChecks;

    @Column(name = "warning_checks", nullable = false)
    private int warningChecks;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checks", nullable = false, columnDefinition = "jsonb")
    private List<GoLiveCheckView> checks;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GoLiveVerification() {
    }

    private GoLiveVerification(UUID organizationId, UUID projectId, UUID environmentId,
            UUID initiatedBy, String idempotencyKey, String state, int readinessPercent, int passedChecks,
            int failedChecks, int warningChecks, List<GoLiveCheckView> checks, Instant verifiedAt) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.initiatedBy = initiatedBy;
        this.idempotencyKey = idempotencyKey;
        this.state = state;
        this.readinessPercent = readinessPercent;
        this.passedChecks = passedChecks;
        this.failedChecks = failedChecks;
        this.warningChecks = warningChecks;
        this.checks = List.copyOf(checks);
        this.verifiedAt = verifiedAt;
    }

    public static GoLiveVerification create(UUID organizationId, UUID projectId, UUID environmentId,
            UUID initiatedBy, String idempotencyKey, String state, int readinessPercent, int passedChecks,
            int failedChecks, int warningChecks, List<GoLiveCheckView> checks, Instant verifiedAt) {
        return new GoLiveVerification(organizationId, projectId, environmentId, initiatedBy, idempotencyKey,
                state, readinessPercent, passedChecks, failedChecks, warningChecks, checks, verifiedAt);
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getState() { return state; }
    public int getReadinessPercent() { return readinessPercent; }
    public int getPassedChecks() { return passedChecks; }
    public int getFailedChecks() { return failedChecks; }
    public int getWarningChecks() { return warningChecks; }
    public List<GoLiveCheckView> getChecks() { return checks; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
