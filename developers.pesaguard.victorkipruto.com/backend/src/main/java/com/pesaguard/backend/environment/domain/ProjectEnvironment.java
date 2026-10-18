package com.pesaguard.backend.environment.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "project_environments")
public class ProjectEnvironment {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 24)
    private EnvironmentType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private EnvironmentStatus status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "configuration", nullable = false, columnDefinition = "jsonb")
    private java.util.Map<String, Object> configuration;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @org.hibernate.annotations.CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @org.hibernate.annotations.UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ProjectEnvironment() {
    }

    private ProjectEnvironment(UUID id, UUID organizationId, UUID projectId, String name,
            EnvironmentType type, UUID createdBy, Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.name = name;
        this.createdBy = createdBy;
        this.type = type;
        this.status = EnvironmentStatus.ACTIVE;
        this.configuration = new java.util.LinkedHashMap<>();
        this.statusChangedAt = now;
    }

    public static ProjectEnvironment create(UUID organizationId, UUID projectId, String name,
            EnvironmentType type, UUID createdBy, Instant now) {
        if (type == null) {
            throw new IllegalArgumentException("An environment type is required");
        }
        return new ProjectEnvironment(UUID.randomUUID(), organizationId, projectId, name, type, createdBy, now);
    }

    public static ProjectEnvironment sandbox(UUID organizationId, UUID projectId, String name, UUID createdBy) {
        return create(organizationId, projectId, name, EnvironmentType.SANDBOX, createdBy, Instant.now());
    }

    public void updateConfiguration(java.util.Map<String, Object> configuration) {
        this.configuration = configuration == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(configuration);
    }

    public void rename(String name) {
        this.name = name.trim();
    }

    public void suspend(Instant now) {
        requireNotDeactivated();
        this.status = EnvironmentStatus.SUSPENDED;
        this.statusChangedAt = now;
    }

    public void resume(Instant now) {
        requireNotDeactivated();
        this.status = EnvironmentStatus.ACTIVE;
        this.statusChangedAt = now;
    }

    public void deactivate(Instant now) {
        this.status = EnvironmentStatus.DEACTIVATED;
        this.statusChangedAt = now;
    }

    /**
     * Promotes this environment exactly one tier. Tier isolation is never
     * bypassed: there is no "skip to production" path, and an environment is
     * never silently reinterpreted as a different tier.
     */
    public void promoteTo(EnvironmentType target, Instant now) {
        requireNotDeactivated();
        if (!type.canPromoteTo(target)) {
            throw new IllegalStateException("Environments may only be promoted one tier at a time");
        }
        this.type = target;
        this.statusChangedAt = now;
    }

    public java.util.Map<String, Object> getConfiguration() {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(configuration));
    }

    public Instant getStatusChangedAt() { return statusChangedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    private void requireNotDeactivated() {
        if (status == EnvironmentStatus.DEACTIVATED) {
            throw new IllegalStateException("A deactivated environment cannot be changed");
        }
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public String getName() { return name; }
    public EnvironmentType getType() { return type; }
    public EnvironmentStatus getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
