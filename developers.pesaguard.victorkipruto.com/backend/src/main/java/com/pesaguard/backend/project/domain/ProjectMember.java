package com.pesaguard.backend.project.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A user's membership of a single project. Removal is recorded as REVOKED
 * rather than deleted so that project access history remains traceable.
 */
@Entity
@Table(name = "project_members")
public class ProjectMember {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 24)
    private ProjectMemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ProjectMemberStatus status;

    @Column(name = "added_by", nullable = false)
    private UUID addedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ProjectMember() {
    }

    private ProjectMember(UUID projectId, UUID organizationId, UUID userId,
            ProjectMemberRole role, UUID addedBy) {
        this.id = UUID.randomUUID();
        this.projectId = projectId;
        this.organizationId = organizationId;
        this.userId = userId;
        this.role = role;
        this.status = ProjectMemberStatus.ACTIVE;
        this.addedBy = addedBy;
    }

    public static ProjectMember add(UUID projectId, UUID organizationId, UUID userId,
            ProjectMemberRole role, UUID addedBy) {
        return new ProjectMember(projectId, organizationId, userId, role, addedBy);
    }

    public void changeRole(ProjectMemberRole newRole) {
        if (status != ProjectMemberStatus.ACTIVE) {
            throw new IllegalStateException("A revoked project membership cannot change role");
        }
        this.role = newRole;
    }

    public void revoke() {
        if (status == ProjectMemberStatus.ACTIVE) {
            status = ProjectMemberStatus.REVOKED;
        }
    }

    public void restore() {
        status = ProjectMemberStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == ProjectMemberStatus.ACTIVE;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public ProjectMemberRole getRole() { return role; }
    public ProjectMemberStatus getStatus() { return status; }
    public UUID getAddedBy() { return addedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
