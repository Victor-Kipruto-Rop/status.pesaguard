package com.pesaguard.backend.oauth.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An issued access token. Short-lived and opaque: only the HMAC is stored, so
 * the token cannot be read back out of the database.
 */
@Entity
@Table(name = "oauth_access_tokens")
public class OAuthAccessToken {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id")
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "scopes", nullable = false, columnDefinition = "text")
    private String scopes;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 64)
    private String revokeReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OAuthAccessToken() {
    }

    private OAuthAccessToken(UUID applicationId, UUID organizationId, UUID userId, UUID familyId,
            String tokenHash, Set<String> scopes, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.organizationId = organizationId;
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.scopes = scopes == null || scopes.isEmpty() ? "" : String.join(",", scopes);
        this.expiresAt = expiresAt;
    }

    public static OAuthAccessToken issue(UUID applicationId, UUID organizationId, UUID userId,
            UUID familyId, String tokenHash, Set<String> scopes, Instant expiresAt) {
        return new OAuthAccessToken(applicationId, organizationId, userId, familyId, tokenHash, scopes, expiresAt);
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now, String reason) {
        if (revokedAt == null) {
            this.revokedAt = now;
            this.revokeReason = reason;
        }
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
    public String getTokenHash() { return tokenHash; }
    public String getScopes() { return scopes; }
    public Set<String> scopeSet() {
        return scopes == null || scopes.isBlank()
                ? Set.of()
                : Set.copyOf(Arrays.asList(scopes.split(",")));
    }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public Instant getCreatedAt() { return createdAt; }
}