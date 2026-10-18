package com.pesaguard.backend.environment.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Per-environment quotas and request limits.
 *
 * <p>{@code requestsPerMinute} and {@code burstRequests} are enforced for
 * API-key-authenticated developer requests by the shared rate-limit store.
 * {@code maxApiKeys} and {@code maxCredentials} are enforced during issuance;
 * the credential rotation interval is surfaced as a frontend reminder. See
 * {@code docs/environment-limits.md} for the exact scope of each limit.
 */
@Entity
@Table(name = "environment_limits")
public class EnvironmentLimits {

    @Id
    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "requests_per_minute", nullable = false)
    private int requestsPerMinute;

    @Column(name = "burst_requests", nullable = false)
    private int burstRequests;

    @Column(name = "max_api_keys", nullable = false)
    private int maxApiKeys;

    @Column(name = "max_credentials", nullable = false)
    private int maxCredentials;

    @Column(name = "credential_rotation_interval_minutes", nullable = false)
    private int credentialRotationIntervalMinutes;

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

    protected EnvironmentLimits() {
    }

    private EnvironmentLimits(UUID environmentId, UUID organizationId, UUID projectId, Instant now) {
        this.environmentId = environmentId;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.requestsPerMinute = 600;
        this.burstRequests = 100;
        this.maxApiKeys = 5;
        this.maxCredentials = 20;
        this.credentialRotationIntervalMinutes = 60;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static EnvironmentLimits defaults(UUID environmentId, UUID organizationId, UUID projectId, Instant now) {
        return new EnvironmentLimits(environmentId, organizationId, projectId, now);
    }

    public void update(int requestsPerMinute, int burstRequests, int maxApiKeys, int maxCredentials,
            int credentialRotationIntervalMinutes, UUID updatedBy, Instant now) {
        this.requestsPerMinute = requestsPerMinute;
        this.burstRequests = burstRequests;
        this.maxApiKeys = maxApiKeys;
        this.maxCredentials = maxCredentials;
        this.credentialRotationIntervalMinutes = credentialRotationIntervalMinutes;
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }

    public UUID getEnvironmentId() { return environmentId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public int getBurstRequests() { return burstRequests; }
    public int getMaxApiKeys() { return maxApiKeys; }
    public int getMaxCredentials() { return maxCredentials; }
    public int getCredentialRotationIntervalMinutes() { return credentialRotationIntervalMinutes; }
    public UUID getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
