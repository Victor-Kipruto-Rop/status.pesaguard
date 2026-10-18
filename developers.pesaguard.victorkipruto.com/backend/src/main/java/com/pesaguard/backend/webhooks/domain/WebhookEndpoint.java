package com.pesaguard.backend.webhooks.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "signing_secret_ciphertext", nullable = false, columnDefinition = "text")
    private String signingSecretCiphertext;

    @Column(nullable = false, length = 24)
    private String status;

    @Version
    @Column(name = "version_lock", nullable = false)
    private long versionLock;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookEndpoint() {
    }

    private WebhookEndpoint(UUID organizationId, UUID projectId, UUID environmentId, String name,
            String url, String signingSecretCiphertext) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.name = name.trim();
        this.url = url;
        this.signingSecretCiphertext = signingSecretCiphertext;
        this.status = "ACTIVE";
    }

    public static WebhookEndpoint create(UUID organizationId, UUID projectId, UUID environmentId,
            String name, String url, String encryptedSecret) {
        return new WebhookEndpoint(organizationId, projectId, environmentId,
                name, url, encryptedSecret);
    }

    public void suspend() {
        if ("DELETED".equals(status)) {
            throw new IllegalStateException("A deleted webhook endpoint cannot be changed");
        }
        status = "SUSPENDED";
    }

    public void resume() {
        if ("DELETED".equals(status)) {
            throw new IllegalStateException("A deleted webhook endpoint cannot be changed");
        }
        status = "ACTIVE";
    }

    public void updateConfiguration(String name, String url) {
        if ("DELETED".equals(status)) {
            throw new IllegalStateException("A deleted webhook endpoint cannot be changed");
        }
        this.name = name.trim();
        this.url = url;
    }

    public void rotateSigningSecret(String encryptedSecret) {
        if ("DELETED".equals(status)) {
            throw new IllegalStateException("A deleted webhook endpoint cannot be changed");
        }
        this.signingSecretCiphertext = encryptedSecret;
    }

    public void delete() {
        status = "DELETED";
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getSigningSecretCiphertext() { return signingSecretCiphertext; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
