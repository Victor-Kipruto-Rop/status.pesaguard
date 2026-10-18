package com.pesaguard.backend.rbac.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A custom role granted to a member. Removal sets {@code revokedAt} rather than
 * deleting the row so that role history stays inspectable and auditable.
 */
@Entity
@Table(name = "organization_role_assignments")
public class RoleAssignment {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "assigned_by", nullable = false)
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    protected RoleAssignment() {
    }

    private RoleAssignment(UUID organizationId, UUID userId, UUID roleId, UUID assignedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.userId = userId;
        this.roleId = roleId;
        this.assignedBy = assignedBy;
        this.assignedAt = now;
    }

    public static RoleAssignment grant(UUID organizationId, UUID userId, UUID roleId, UUID assignedBy, Instant now) {
        return new RoleAssignment(organizationId, userId, roleId, assignedBy, now);
    }

    public void revoke(UUID revokedBy, Instant now) {
        if (revokedAt == null) {
            this.revokedBy = revokedBy;
            this.revokedAt = now;
        }
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public UUID getRoleId() { return roleId; }
    public UUID getAssignedBy() { return assignedBy; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public UUID getRevokedBy() { return revokedBy; }
}