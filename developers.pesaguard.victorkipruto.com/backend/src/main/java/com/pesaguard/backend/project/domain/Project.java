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

@Entity
@Table(name = "projects")
public class Project {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "slug", nullable = false, length = 80)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ProjectStatus status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "description", length = 500)
    private String description;

    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private long version;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private java.util.Map<String, Object> metadata;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Project() {
    }

    private Project(UUID id, UUID organizationId, String name, String slug,
            UUID createdBy, java.time.Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.name = name;
        this.slug = slug;
        this.createdBy = createdBy;
        this.ownerUserId = createdBy;
        this.status = ProjectStatus.ACTIVE;
        this.metadata = new java.util.LinkedHashMap<>();
        this.statusChangedAt = now;
    }

    public static Project create(UUID organizationId, String name, String slug, UUID createdBy, java.time.Instant now) {
        return new Project(UUID.randomUUID(), organizationId, name, slug, createdBy, now);
    }

    public void update(String name, String description, java.util.Map<String, Object> metadata) {
        this.name = name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.metadata = metadata == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(metadata);
    }

    public void archive(java.time.Instant now) {
        if (status == ProjectStatus.ARCHIVED) {
            return;
        }
        status = ProjectStatus.ARCHIVED;
        statusChangedAt = now;
    }

    public void restore(java.time.Instant now) {
        if (status == ProjectStatus.ARCHIVED) {
            status = ProjectStatus.ACTIVE;
            statusChangedAt = now;
        }
    }

    public void deactivate(java.time.Instant now) {
        if (status == ProjectStatus.ARCHIVED) {
            throw new IllegalStateException("An archived project cannot be deactivated");
        }
        status = ProjectStatus.DEACTIVATED;
        statusChangedAt = now;
    }

    public void activate(java.time.Instant now) {
        if (status == ProjectStatus.ARCHIVED) {
            throw new IllegalStateException("An archived project must be restored before it can be reactivated");
        }
        status = ProjectStatus.ACTIVE;
        statusChangedAt = now;
    }

    public void transferOwnership(UUID newOwnerUserId) {
        this.ownerUserId = newOwnerUserId;
    }

    public java.util.Map<String, Object> getMetadata() {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
    }

    public UUID getOwnerUserId() { return ownerUserId; }
    public String getDescription() { return description; }
    public Instant getStatusChangedAt() { return statusChangedAt; }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public ProjectStatus getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
