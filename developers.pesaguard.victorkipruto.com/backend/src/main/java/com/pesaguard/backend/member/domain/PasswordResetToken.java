package com.pesaguard.backend.member.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single-use password reset token, stored as an HMAC.
 *
 * <p>HMAC rather than a bare digest so that a leaked table cannot be brute-forced
 * offline: the attacker would also need the server's key. The same reasoning
 * already applies to session tokens and API keys in this codebase.
 *
 * <p>The token is scoped to the <em>account</em>, not to an organization. A
 * password reset authorises a full account takeover, so it has to end every
 * session the user holds in every organization they belong to. Attaching it to
 * whichever organization happened to be current would be misleading at best,
 * and would invite a later reader to scope revocation by it.
 */
@Entity
@Table(name = "user_password_reset_tokens")
public class PasswordResetToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

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

    @Column(name = "requested_ip", length = 45)
    private String requestedIp;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PasswordResetToken() {
    }

    private PasswordResetToken(UUID id, UUID userId, String tokenHash,
            Instant issuedAt, Instant expiresAt, String requestedIp) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.requestedIp = requestedIp;
    }

    public static PasswordResetToken issue(UUID userId, String tokenHash,
            Instant issuedAt, Instant expiresAt, String requestedIp) {
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("A reset token must expire after it is issued");
        }
        return new PasswordResetToken(UUID.randomUUID(), userId, tokenHash,
                issuedAt, expiresAt, truncate(requestedIp));
    }

    /**
     * Whether this token may still be redeemed.
     *
     * <p>Checked at redemption time rather than only at issue, so a token that
     * expires while sitting in an email is refused rather than honoured.
     */
    public boolean isRedeemable(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now) {
        this.usedAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= 45 ? trimmed : trimmed.substring(0, 45);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRequestedIp() { return requestedIp; }
    public Instant getCreatedAt() { return createdAt; }
}