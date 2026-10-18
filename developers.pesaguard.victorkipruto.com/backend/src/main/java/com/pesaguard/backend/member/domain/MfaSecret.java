package com.pesaguard.backend.member.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An enrolled TOTP factor.
 *
 * <p>The secret is stored encrypted rather than hashed, because verifying a
 * six-digit code requires recomputing it from the secret. That makes this the one
 * credential in the system where confidentiality of the stored value is the
 * control, which is why it is AES-GCM ciphertext and nothing else.
 *
 * <p>{@code confirmedAt} is null between enrolment and the user proving
 * possession. An unconfirmed secret is never consulted during a login challenge:
 * otherwise anyone who started enrolment on a victim's session, or intercepted
 * the enrolment response, could enable a factor they alone can satisfy.
 */
@Entity
@Table(name = "user_mfa_secrets")
public class MfaSecret {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "secret_ciphertext", nullable = false)
    private String secretCiphertext;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * Highest TOTP step already accepted for this factor.
     *
     * <p>Persisted because TOTP codes are valid for a whole 30 second step, so
     * without this a code observed once would remain usable until the step ended.
     * Advancing this on every success makes each code single-use.
     */
    @Column(name = "last_used_counter", nullable = false)
    private long lastUsedCounter;

    protected MfaSecret() {
    }

    private MfaSecret(UUID id, UUID userId, String secretCiphertext) {
        this.id = id;
        this.userId = userId;
        this.secretCiphertext = secretCiphertext;
    }

    public static MfaSecret enrol(UUID userId, String secretCiphertext) {
        return new MfaSecret(UUID.randomUUID(), userId, secretCiphertext);
    }

    /** Marks the factor proven, after the user submitted a valid code from it. */
    public void confirm(Instant now) {
        this.confirmedAt = now;
    }

    /**
     * Accepts a code only if it is newer than any already accepted.
     *
     * @return true if the step was strictly newer and has now been consumed
     */
    public boolean consumeStep(long counter) {
        if (counter <= lastUsedCounter) {
            return false;
        }
        this.lastUsedCounter = counter;
        return true;
    }

    public boolean isConfirmed() {
        return confirmedAt != null && revokedAt == null;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getSecretCiphertext() { return secretCiphertext; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public long getLastUsedCounter() { return lastUsedCounter; }
}