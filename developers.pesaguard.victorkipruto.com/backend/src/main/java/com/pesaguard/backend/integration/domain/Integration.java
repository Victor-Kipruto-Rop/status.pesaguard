package com.pesaguard.backend.integration.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.pesaguard.backend.environment.domain.EnvironmentType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "integrations")
public class Integration {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment_type", nullable = false, length = 24)
    private EnvironmentType environmentType;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private IntegrationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "health_status", nullable = false, length = 24)
    private IntegrationHealthStatus healthStatus;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "last_request_id")
    private UUID lastRequestId;

    @Column(name = "last_trace_id", length = 128)
    private String lastTraceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Integration() {
    }

    private Integration(UUID organizationId, UUID projectId, UUID environmentId,
            EnvironmentType environmentType, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.environmentType = environmentType;
        this.type = "PESAGUARD_API";
        this.provider = "PESAGUARD";
        this.status = IntegrationStatus.NOT_CONFIGURED;
        this.healthStatus = IntegrationHealthStatus.UNKNOWN;
        this.displayName = "PesaGuard API";
        this.description = "Environment-scoped connection to the PesaGuard API.";
        this.enabled = true;
        this.updatedAt = now;
    }

    public static Integration createDefault(UUID organizationId, UUID projectId,
            UUID environmentId, EnvironmentType environmentType, Instant now) {
        return new Integration(organizationId, projectId, environmentId, environmentType, now);
    }

    public void recordTest(boolean success, Instant testedAt, UUID requestId, String traceId) {
        boolean hadSuccess = lastSuccessAt != null;
        this.lastTestedAt = testedAt;
        this.lastRequestId = requestId;
        this.lastTraceId = traceId;
        if (success) {
            this.status = IntegrationStatus.CONNECTED;
            this.healthStatus = IntegrationHealthStatus.HEALTHY;
            this.lastSuccessAt = testedAt;
        } else {
            this.status = hadSuccess ? IntegrationStatus.DEGRADED : IntegrationStatus.FAILED;
            this.healthStatus = hadSuccess ? IntegrationHealthStatus.DEGRADED : IntegrationHealthStatus.UNKNOWN;
            this.lastFailureAt = testedAt;
        }
    }

    public void startTest(UUID requestId) {
        if (!enabled) throw new IllegalStateException("A disabled integration cannot be tested");
        this.status = IntegrationStatus.TESTING;
        this.lastRequestId = requestId;
    }

    public void enable() {
        this.enabled = true;
        if (lastSuccessAt == null) {
            this.status = IntegrationStatus.NOT_CONFIGURED;
            this.healthStatus = IntegrationHealthStatus.UNKNOWN;
        } else {
            this.status = healthStatus == IntegrationHealthStatus.HEALTHY
                    ? IntegrationStatus.CONNECTED : IntegrationStatus.DEGRADED;
        }
    }

    public void disable() {
        this.enabled = false;
        this.status = IntegrationStatus.DISABLED;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public EnvironmentType getEnvironmentType() { return environmentType; }
    public String getType() { return type; }
    public String getProvider() { return provider; }
    public IntegrationStatus getStatus() { return status; }
    public IntegrationHealthStatus getHealthStatus() { return healthStatus; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
    public Instant getLastTestedAt() { return lastTestedAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getLastFailureAt() { return lastFailureAt; }
    public UUID getLastRequestId() { return lastRequestId; }
    public String getLastTraceId() { return lastTraceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
