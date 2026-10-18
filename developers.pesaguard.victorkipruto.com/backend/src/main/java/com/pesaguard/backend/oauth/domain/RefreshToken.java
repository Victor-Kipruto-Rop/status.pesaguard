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
 * A refresh token belonging to a token family.
 *
 * <p>Every refresh rotates the token: the presented token is marked used and
 * replaced. Presenting an already-used or revoked token is treated as a possible
 * theft and revokes the whole family, which is the standard defence against
 * refresh token replay.
 */
@Entity
@Table(name = "oauth_refresh_tokens")
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "scopes", nullable = false, columnDefinition = "text")
    private String scopes;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 64)
    private String revokeReason;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
    }

    private RefreshToken(UUID applicationId, UUID organizationId, UUID userId, UUID familyId,
            String tokenHash, Set<String> scopes, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.organizationId = organizationId;
        this.userId = userId;
        this.familyId = familyId == null ? UUID.randomUUID() : familyId;
        this.tokenHash = tokenHash;
        this.scopes = scopes == null || scopes.isEmpty() ? "" : String.join(",", scopes);
        this.expiresAt = expiresAt;
    }

    public static RefreshToken issue(UUID applicationId, UUID organizationId, UUID userId,
            UUID familyId, String tokenHash, Set<String> scopes, Instant expiresAt) {
        return new RefreshToken(applicationId, organizationId, userId, familyId, tokenHash, scopes, expiresAt);
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    /** Marks this token as spent by a successful refresh. */
    public void markUsed(Instant now, UUID replacementId) {
        if (usedAt != null) {
            throw new IllegalStateException("This refresh token has already been used");
        }
        this.usedAt = now;
        this.replacedById = replacementId;
    }

    public void revoke(Instant now, String reason) {
        if (revokedAt == null) {
            this.revokedAt = now;
            this.revokeReason = reason;
        }
    }

    /** True when this token was already spent or revoked: a replay signal. */
    public boolean isReplayed() {
        return usedAt != null || revokedAt != null;
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
    public Instant getUsedAt() { return usedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public UUID getReplacedById() { return replacedById; }
    public Instant getCreatedAt() { return createdAt; }
}