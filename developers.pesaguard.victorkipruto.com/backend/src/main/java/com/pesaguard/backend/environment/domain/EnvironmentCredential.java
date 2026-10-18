package com.pesaguard.backend.environment.domain;

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

/**
 * A secret belonging to exactly one environment tier.
 *
 * <p>The HMAC hash and keyed fingerprint support comparison without disclosing
 * the secret. The value is also stored as authenticated ciphertext for future
 * internal runtime consumers; developer APIs expose metadata only. Rotating
 * creates a new version rather than overwriting history.
 */
@Entity
@Table(name = "environment_credentials")
public class EnvironmentCredential {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false, length = 32)
    private EnvironmentCredentialType credentialType;

    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "encrypted_secret", columnDefinition = "text")
    private String encryptedSecret;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private EnvironmentCredentialStatus status;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EnvironmentCredential() {
    }

    private EnvironmentCredential(UUID organizationId, UUID projectId, UUID environmentId, String name,
            EnvironmentCredentialType credentialType, String secretHash, String encryptedSecret, String fingerprint,
            int version, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.name = name;
        this.credentialType = credentialType;
        this.secretHash = secretHash;
        this.encryptedSecret = encryptedSecret;
        this.fingerprint = fingerprint;
        this.version = version;
        this.createdBy = createdBy;
        this.status = EnvironmentCredentialStatus.ACTIVE;
    }

    public static EnvironmentCredential create(UUID organizationId, UUID projectId, UUID environmentId,
            String name, EnvironmentCredentialType credentialType, String secretHash, String encryptedSecret, String fingerprint,
            int version, UUID createdBy) {
        return new EnvironmentCredential(organizationId, projectId, environmentId, name,
                credentialType, secretHash, encryptedSecret, fingerprint, version, createdBy);
    }

    public void revoke(Instant now) {
        if (status == EnvironmentCredentialStatus.ACTIVE) {
            status = EnvironmentCredentialStatus.REVOKED;
            this.rotatedAt = now;
        }
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public String getName() { return name; }
    public EnvironmentCredentialType getCredentialType() { return credentialType; }
    public int getVersion() { return version; }
    public EnvironmentCredentialStatus getStatus() { return status; }
    public Instant getRotatedAt() { return rotatedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
