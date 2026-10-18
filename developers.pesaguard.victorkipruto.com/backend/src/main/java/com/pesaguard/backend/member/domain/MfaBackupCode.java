package com.pesaguard.backend.member.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single-use recovery code for an enrolled factor.
 *
 * <p>Stored as a SHA-256 digest, never as the code itself. Recovery codes are
 * high-entropy random values rather than user-chosen passwords, so a bare digest
 * resists offline guessing and keeps the codes unreadable after issuance.
 *
 * <p>Codes belong to a {@link MfaSecret} rather than directly to a user so that
 * regenerating a factor's codes revokes the previous set as a consequence,
 * instead of leaving orphaned codes that still satisfy an old challenge.
 */
@Entity
@Table(name = "user_mfa_backup_codes")
public class MfaBackupCode {

    @Id
    private UUID id;

    @Column(name = "secret_id", nullable = false)
    private UUID secretId;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected MfaBackupCode() {
    }

    private MfaBackupCode(UUID id, UUID secretId, String codeHash) {
        this.id = id;
        this.secretId = secretId;
        this.codeHash = codeHash;
    }

    public static MfaBackupCode issue(UUID secretId, String codeHash) {
        return new MfaBackupCode(UUID.randomUUID(), secretId, codeHash);
    }

    public boolean isUnused() {
        return usedAt == null;
    }

    /** Marks the code spent. A second use of the same code therefore fails. */
    public void consume(Instant now) {
        this.usedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getSecretId() { return secretId; }
    public String getCodeHash() { return codeHash; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCreatedAt() { return createdAt; }
}