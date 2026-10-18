package com.pesaguard.backend.member.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A family of rotating refresh tokens, one per login.
 *
 * <p>The family is the unit of revocation. Revoking the family rather than a
 * single token is what makes reuse detection meaningful: once a replay is seen,
 * there is no safe way to tell how far the attacker got, so every outstanding
 * descendant of that login is killed and the user must re-authenticate.
 */
@Entity
@Table(name = "user_refresh_token_families")
public class RefreshTokenFamily {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_rotated_at")
    private Instant lastRotatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * Why the family was killed: REUSE_DETECTED, PASSWORD_RESET, LOGOUT_ALL.
     *
     * <p>Recorded rather than inferred, because "why did my session die" is the
     * first question in any incident review and the answer is otherwise
     * unrecoverable after the fact.
     */
    @Column(name = "revoked_reason", length = 48)
    private String revokedReason;

    protected RefreshTokenFamily() {
    }

    private RefreshTokenFamily(UUID id, UUID userId, UUID organizationId) {
        this.id = id;
        this.userId = userId;
        this.organizationId = organizationId;
    }

    public static RefreshTokenFamily start(UUID userId, UUID organizationId) {
        if (userId == null || organizationId == null) {
            throw new IllegalArgumentException("A refresh family belongs to a user and organization");
        }
        return new RefreshTokenFamily(UUID.randomUUID(), userId, organizationId);
    }

    public void recordRotation(Instant now) {
        this.lastRotatedAt = now;
    }

    /** Revokes the family. Idempotent, so the first recorded reason is kept. */
    public void revoke(String reason, Instant now) {
        if (revokedAt != null) {
            return;
        }
        this.revokedAt = now;
        this.revokedReason = reason;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getOrganizationId() { return organizationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastRotatedAt() { return lastRotatedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokedReason() { return revokedReason; }
}