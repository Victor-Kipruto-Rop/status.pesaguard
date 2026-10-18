package com.pesaguard.backend.serviceaccount.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "service_accounts")
public class ServiceAccount {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "client_id", nullable = false, unique = true, length = 64)
    private String clientId;

    @Column(name = "client_secret_hash", nullable = false, length = 64)
    private String clientSecretHash;

    @Column(name = "client_secret_hint", nullable = false, length = 16)
    private String clientSecretHint;

    @Column(nullable = false, columnDefinition = "text")
    private String scopes;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ServiceAccount() {
    }

    private ServiceAccount(UUID organizationId, String name, String description, String clientId,
            String secretHash, String secretHint, Set<String> scopes, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.name = name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.clientId = clientId;
        this.clientSecretHash = secretHash;
        this.clientSecretHint = secretHint;
        this.scopes = encode(scopes);
        this.status = "ACTIVE";
        this.createdBy = createdBy;
    }

    public static ServiceAccount create(UUID organizationId, String name, String description,
            String clientId, String secretHash, String secretHint, Set<String> scopes, UUID createdBy) {
        return new ServiceAccount(organizationId, name, description, clientId,
                secretHash, secretHint, scopes, createdBy);
    }

    public void update(String name, String description, Set<String> scopes) {
        requireNotRevoked();
        this.name = name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.scopes = encode(scopes);
    }

    public void rotateSecret(String secretHash, String secretHint) {
        requireNotRevoked();
        this.clientSecretHash = secretHash;
        this.clientSecretHint = secretHint;
    }

    public void suspend() {
        requireNotRevoked();
        if ("SUSPENDED".equals(status)) {
            return;
        }
        this.status = "SUSPENDED";
    }

    public void resume() {
        requireNotRevoked();
        if (!"SUSPENDED".equals(status)) {
            throw new IllegalStateException("Only a suspended service account can be resumed.");
        }
        this.status = "ACTIVE";
    }

    public void revoke(Instant now) {
        if (!"REVOKED".equals(status)) {
            this.status = "REVOKED";
            this.revokedAt = now;
        }
    }

    private void requireNotRevoked() {
        if ("REVOKED".equals(status)) {
            throw new IllegalStateException("A revoked service account cannot be changed.");
        }
    }

    private static String encode(Set<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(",", values.stream().sorted().toList());
    }

    public Set<String> scopeSet() {
        return scopes == null || scopes.isBlank()
                ? Set.of()
                : Set.copyOf(Arrays.asList(scopes.split(",")));
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getClientId() { return clientId; }
    public String getClientSecretHash() { return clientSecretHash; }
    public String getClientSecretHint() { return clientSecretHint; }
    public String getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
