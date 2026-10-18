package com.pesaguard.backend.organization.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "organization_invitation_email_deliveries")
public class InvitationEmailDelivery {

    private static final int MAX_ATTEMPTS = 6;

    @Id
    private UUID id;

    @Column(name = "invitation_id", nullable = false)
    private UUID invitationId;

    @Column(name = "recipient_email", nullable = false, length = 320)
    private String recipientEmail;

    @Column(name = "organization_name", nullable = false, length = 120)
    private String organizationName;

    @Column(name = "inviter_name", nullable = false, length = 120)
    private String inviterName;

    @Column(name = "invitation_role", nullable = false, length = 24)
    private String role;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "token_ciphertext", columnDefinition = "text")
    private String tokenCiphertext;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private InvitationEmailDeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", length = 120)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InvitationEmailDelivery() {
    }

    private InvitationEmailDelivery(UUID invitationId, String recipientEmail, String organizationName,
            String inviterName, String role, String tokenHash, String tokenCiphertext,
            Instant expiresAt, Instant now) {
        this.id = UUID.randomUUID();
        this.invitationId = invitationId;
        this.recipientEmail = recipientEmail;
        this.organizationName = organizationName;
        this.inviterName = inviterName;
        this.role = role;
        this.tokenHash = tokenHash;
        this.tokenCiphertext = tokenCiphertext;
        this.expiresAt = expiresAt;
        this.status = InvitationEmailDeliveryStatus.PENDING;
        this.nextAttemptAt = now;
    }

    public static InvitationEmailDelivery queue(UUID invitationId, String recipientEmail,
            String organizationName, String inviterName, String role, String tokenHash,
            String tokenCiphertext, Instant expiresAt, Instant now) {
        return new InvitationEmailDelivery(invitationId, recipientEmail, organizationName,
                inviterName, role, tokenHash, tokenCiphertext, expiresAt, now);
    }

    public void markSent(Instant now) {
        status = InvitationEmailDeliveryStatus.SENT;
        sentAt = now;
        tokenCiphertext = null;
    }

    public void markFailed(String errorType, Instant now) {
        attemptCount++;
        lastError = errorType;
        if (attemptCount >= MAX_ATTEMPTS || !expiresAt.isAfter(now)) {
            status = InvitationEmailDeliveryStatus.FAILED;
            tokenCiphertext = null;
            return;
        }
        long retrySeconds = Math.min(3600L, 15L << Math.min(attemptCount - 1, 8));
        nextAttemptAt = now.plusSeconds(retrySeconds);
    }

    public void cancel() {
        status = InvitationEmailDeliveryStatus.CANCELLED;
        tokenCiphertext = null;
    }

    public UUID getId() { return id; }
    public UUID getInvitationId() { return invitationId; }
    public String getRecipientEmail() { return recipientEmail; }
    public String getOrganizationName() { return organizationName; }
    public String getInviterName() { return inviterName; }
    public String getRole() { return role; }
    public String getTokenHash() { return tokenHash; }
    public String getTokenCiphertext() { return tokenCiphertext; }
    public Instant getExpiresAt() { return expiresAt; }
    public InvitationEmailDeliveryStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getSentAt() { return sentAt; }
    public String getLastError() { return lastError; }
}
