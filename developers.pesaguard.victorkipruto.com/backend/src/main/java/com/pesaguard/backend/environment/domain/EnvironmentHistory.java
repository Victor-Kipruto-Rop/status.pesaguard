package com.pesaguard.backend.environment.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Append-only record of an environment lifecycle change. Rows are never updated
 * or deleted; the database trigger rejects both.
 */
@Entity
@Table(name = "environment_history")
public class EnvironmentHistory {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    // Persisted by name, never by ordinal. Ordinal storage would silently
    // reassign meaning if a constant is ever inserted into the enum, rewriting
    // history rows that are append-only and therefore cannot be corrected.
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 24)
    private EnvironmentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 24)
    private EnvironmentStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_type", length = 24)
    private EnvironmentType fromType;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_type", nullable = false, length = 24)
    private EnvironmentType toType;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EnvironmentHistory() {
    }

    private EnvironmentHistory(UUID organizationId, UUID projectId, UUID environmentId,
            EnvironmentStatus fromStatus, EnvironmentStatus toStatus,
            EnvironmentType fromType, EnvironmentType toType,
            String action, UUID actorUserId, String reason, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.fromType = fromType;
        this.toType = toType;
        this.action = action;
        this.actorUserId = actorUserId;
        this.reason = reason;
        this.createdAt = now;
    }

    public static EnvironmentHistory record(UUID organizationId, UUID projectId, UUID environmentId,
            EnvironmentStatus fromStatus, EnvironmentType fromType, String action,
            UUID actorUserId, String reason, Instant now) {
        return record(organizationId, projectId, environmentId, fromStatus, fromType,
                EnvironmentStatus.ACTIVE, fromType, action, actorUserId, reason, now);
    }

    public static EnvironmentHistory record(UUID organizationId, UUID projectId, UUID environmentId,
            EnvironmentStatus fromStatus, EnvironmentType fromType,
            EnvironmentStatus toStatus, EnvironmentType toType, String action,
            UUID actorUserId, String reason, Instant now) {
        return new EnvironmentHistory(organizationId, projectId, environmentId,
                fromStatus, toStatus, fromType, toType, action, actorUserId, reason, now);
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public EnvironmentStatus getFromStatus() { return fromStatus; }
    public EnvironmentStatus getToStatus() { return toStatus; }
    public EnvironmentType getFromType() { return fromType; }
    public EnvironmentType getToType() { return toType; }
    public String getAction() { return action; }
    public UUID getActorUserId() { return actorUserId; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}