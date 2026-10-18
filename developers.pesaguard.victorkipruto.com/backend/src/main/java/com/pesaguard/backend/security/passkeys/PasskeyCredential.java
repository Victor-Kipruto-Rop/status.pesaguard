package com.pesaguard.backend.security.passkeys;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "passkey_credentials")
public class PasskeyCredential {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "credential_id", nullable = false, unique = true)
    private byte[] credentialId;

    @Column(name = "user_handle", nullable = false)
    private byte[] userHandle;

    @Column(name = "public_key_cose", nullable = false)
    private byte[] publicKeyCose;

    @Column(name = "signature_count", nullable = false)
    private long signatureCount;

    @Column(name = "backup_eligible", nullable = false)
    private boolean backupEligible;

    @Column(name = "backed_up", nullable = false)
    private boolean backedUp;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected PasskeyCredential() {
    }

    public static PasskeyCredential create(UUID userId, byte[] credentialId, byte[] userHandle,
            byte[] publicKeyCose, long signatureCount, boolean backupEligible, boolean backedUp,
            String displayName, Instant createdAt) {
        PasskeyCredential credential = new PasskeyCredential();
        credential.id = UUID.randomUUID();
        credential.userId = userId;
        credential.credentialId = credentialId.clone();
        credential.userHandle = userHandle.clone();
        credential.publicKeyCose = publicKeyCose.clone();
        credential.signatureCount = signatureCount;
        credential.backupEligible = backupEligible;
        credential.backedUp = backedUp;
        credential.displayName = displayName;
        credential.createdAt = createdAt;
        return credential;
    }

    public void recordUse(long counter, boolean eligible, boolean backedUp, Instant usedAt) {
        this.signatureCount = counter;
        this.backupEligible = eligible;
        this.backedUp = backedUp;
        this.lastUsedAt = usedAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public byte[] getCredentialId() { return credentialId.clone(); }
    public byte[] getUserHandle() { return userHandle.clone(); }
    public byte[] getPublicKeyCose() { return publicKeyCose.clone(); }
    public long getSignatureCount() { return signatureCount; }
    public boolean isBackupEligible() { return backupEligible; }
    public boolean isBackedUp() { return backedUp; }
    public String getDisplayName() { return displayName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
}
