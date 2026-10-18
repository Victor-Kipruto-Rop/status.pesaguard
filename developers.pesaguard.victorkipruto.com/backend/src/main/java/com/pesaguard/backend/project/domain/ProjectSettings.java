package com.pesaguard.backend.project.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "project_settings")
public class ProjectSettings {

    @Id
    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ProjectSettings() {
    }

    private ProjectSettings(UUID projectId, UUID organizationId, Map<String, Object> settings, Instant now) {
        this.projectId = projectId;
        this.organizationId = organizationId;
        this.settings = settings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(settings);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ProjectSettings defaults(UUID projectId, UUID organizationId, Instant now) {
        return new ProjectSettings(projectId, organizationId, Map.of(), now);
    }

    public static ProjectSettings of(UUID projectId, UUID organizationId,
            Map<String, Object> settings, UUID updatedBy, Instant now) {
        ProjectSettings instance = new ProjectSettings(projectId, organizationId, settings, now);
        instance.updatedBy = updatedBy;
        return instance;
    }

    public void update(Map<String, Object> newSettings, UUID updatedBy, Instant now) {
        this.settings = newSettings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(newSettings);
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }

    public Map<String, Object> getSettings() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    public UUID getProjectId() { return projectId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}