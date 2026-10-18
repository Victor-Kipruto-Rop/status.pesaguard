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
import jakarta.persistence.Version;

@Entity
@Table(name = "organization_invitations")
public class OrganizationInvitation {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 24)
    private OrganizationRole role;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "idempotency_key_hash", length = 64)
    private String idempotencyKeyHash;

    @Column(name = "idempotency_request_hash", length = 64)
    private String idempotencyRequestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private InvitationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected OrganizationInvitation() {
    }

    private OrganizationInvitation(UUID id, UUID organizationId, String email, OrganizationRole role,
            String tokenHash, String idempotencyKeyHash, String idempotencyRequestHash,
            Instant expiresAt, UUID invitedBy) {
        this.id = id;
        this.organizationId = organizationId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.idempotencyKeyHash = idempotencyKeyHash;
        this.idempotencyRequestHash = idempotencyRequestHash;
        this.status = InvitationStatus.PENDING;
        this.expiresAt = expiresAt;
        this.invitedBy = invitedBy;
    }

    public static OrganizationInvitation create(UUID organizationId, String email, OrganizationRole role,
            String tokenHash, String idempotencyKeyHash, String idempotencyRequestHash,
            Instant expiresAt, UUID invitedBy) {
        return new OrganizationInvitation(UUID.randomUUID(), organizationId, email, role, tokenHash,
                idempotencyKeyHash, idempotencyRequestHash, expiresAt, invitedBy);
    }

    public void accept(UUID userId, Instant now) {
        if (status != InvitationStatus.PENDING || !expiresAt.isAfter(now)) {
            throw new IllegalStateException("Invitation is no longer pending");
        }
        status = InvitationStatus.ACCEPTED;
        acceptedBy = userId;
        acceptedAt = now;
    }

    public void revoke(Instant now) {
        if (status == InvitationStatus.PENDING) {
            status = InvitationStatus.REVOKED;
            revokedAt = now;
        }
    }

    public void decline(Instant now) {
        if (status != InvitationStatus.PENDING || !expiresAt.isAfter(now)) {
            throw new IllegalStateException("Invitation is no longer pending");
        }
        status = InvitationStatus.DECLINED;
        declinedAt = now;
    }

    public void cancel(Instant now) {
        if (status != InvitationStatus.PENDING) {
            throw new IllegalStateException("Invitation is no longer pending");
        }
        status = InvitationStatus.CANCELLED;
        cancelledAt = now;
    }

    public void expire(Instant now) {
        if (status == InvitationStatus.PENDING && !expiresAt.isAfter(now)) {
            status = InvitationStatus.EXPIRED;
        }
    }

    public void rotateToken(String nextTokenHash, Instant nextExpiresAt, Instant now) {
        if ((status != InvitationStatus.PENDING && status != InvitationStatus.EXPIRED)
                || (status == InvitationStatus.PENDING && expiresAt.isAfter(now))) {
            throw new IllegalStateException("Invitation cannot be resent in its current state");
        }
        tokenHash = nextTokenHash;
        expiresAt = nextExpiresAt;
        status = InvitationStatus.PENDING;
        revokedAt = null;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getEmail() { return email; }
    public String getTokenHash() { return tokenHash; }
    public String getIdempotencyKeyHash() { return idempotencyKeyHash; }
    public String getIdempotencyRequestHash() { return idempotencyRequestHash; }
    public OrganizationRole getRole() { return role; }
    public InvitationStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getInvitedBy() { return invitedBy; }
    public UUID getAcceptedBy() { return acceptedBy; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getDeclinedAt() { return declinedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
