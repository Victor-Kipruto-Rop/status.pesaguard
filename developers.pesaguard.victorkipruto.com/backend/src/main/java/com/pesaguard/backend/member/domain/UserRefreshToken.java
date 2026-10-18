package com.pesaguard.backend.member.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A rotating refresh token, stored as a hash.
 *
 * <p>Every rotation mints a new row in the same family and stamps
 * {@code usedAt} on the presented one. That single fact is the whole of the
 * replay-detection design: a legitimate client only ever presents the newest
 * token in its family, so a second presentation of an already-used token means
 * either an attacker or a client that lost its state. Both are treated the same
 * way — the entire family dies — because guessing which one it was would be
 * exactly the judgement this must not attempt mid-attack.
 *
 * <p>Named for its table rather than simply {@code RefreshToken}: an unrelated
 * OAuth grant entity already uses that name, and Hibernate derives the entity
 * name from the class, so two {@code RefreshToken} entities make the persistence
 * unit fail to start.
 */
@Entity
@Table(name = "user_refresh_tokens")
public class UserRefreshToken {

    @Id
    private UUID id;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    /** SHA-256 of the opaque token. The token itself is never persisted. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "device_label", length = 64)
    private String deviceLabel;

    @Column(name = "last_ip", length = 45)
    private String lastIp;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserRefreshToken() {
    }

    private UserRefreshToken(UUID id, UUID familyId, String tokenHash, Instant issuedAt, Instant expiresAt,
            String deviceLabel, String lastIp) {
        this.id = id;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.deviceLabel = deviceLabel;
        this.lastIp = lastIp;
    }

    public static UserRefreshToken issue(UUID familyId, String tokenHash, Instant issuedAt, Instant expiresAt,
            String deviceLabel, String lastIp) {
        if (expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("A refresh token must expire after it is issued");
        }
        return new UserRefreshToken(UUID.randomUUID(), familyId, tokenHash, issuedAt, expiresAt,
                deviceLabel, lastIp);
    }

    /**
     * Marks this token spent at rotation time.
     *
     * <p>Idempotent on purpose. Two concurrent refreshes of the same token are a
     * real race in any browser with two open tabs; both must record the spend
     * rather than the second overwriting the first and leaving the token looking
     * unused, which would hide a genuine replay from the next rotation.
     */
    public void markUsed(Instant now) {
        if (usedAt == null) {
            usedAt = now;
        }
    }

    /** Whether this token has already been rotated away. */
    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRedeemable(Instant now) {
        return usedAt == null && revokedAt == null && !isExpired(now);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID getId() { return id; }
    public UUID getFamilyId() { return familyId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getDeviceLabel() { return deviceLabel; }
    public String getLastIp() { return lastIp; }
    public Instant getCreatedAt() { return createdAt; }
}