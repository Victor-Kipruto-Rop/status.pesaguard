package com.pesaguard.backend.sandbox.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Append-only lifecycle history.
 *
 * <p>Answers "when was this sandbox suspended, by whom, and why" without
 * reconstructing it from the current row. The database rejects updates and
 * deletes, because a lifecycle log that can be edited is not evidence.
 */
@Entity
@Table(name = "sandbox_history")
public class SandboxHistory {

    @Id
    private UUID id;

    @Column(name = "sandbox_id", nullable = false)
    private UUID sandboxId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 24)
    private SandboxStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 24)
    private SandboxStatus toStatus;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "reason", length = 500)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SandboxHistory() {
    }

    private SandboxHistory(UUID sandboxId, UUID organizationId, SandboxStatus fromStatus,
            SandboxStatus toStatus, String action, UUID actorUserId, String reason) {
        this.id = UUID.randomUUID();
        this.sandboxId = sandboxId;
        this.organizationId = organizationId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.action = action;
        this.actorUserId = actorUserId;
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    public static SandboxHistory record(Sandbox sandbox, SandboxStatus fromStatus, String action,
            UUID actorUserId, String reason) {
        return new SandboxHistory(sandbox.getId(), sandbox.getOrganizationId(), fromStatus,
                sandbox.getStatus(), action, actorUserId, reason);
    }

    public UUID getId() { return id; }
    public UUID getSandboxId() { return sandboxId; }
    public UUID getOrganizationId() { return organizationId; }
    public SandboxStatus getFromStatus() { return fromStatus; }
    public SandboxStatus getToStatus() { return toStatus; }
    public String getAction() { return action; }
    public UUID getActorUserId() { return actorUserId; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}