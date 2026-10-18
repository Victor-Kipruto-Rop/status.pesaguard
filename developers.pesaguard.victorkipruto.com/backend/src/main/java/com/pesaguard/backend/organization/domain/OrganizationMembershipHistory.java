package com.pesaguard.backend.organization.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "organization_membership_history")
public class OrganizationMembershipHistory {

    @Id
    private UUID id;

    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 24)
    private MembershipStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 24)
    private MembershipStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_role", length = 24)
    private OrganizationRole fromRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_role", nullable = false, length = 24)
    private OrganizationRole toRole;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrganizationMembershipHistory() {
    }

    private OrganizationMembershipHistory(UUID membershipId, UUID organizationId, UUID userId,
            MembershipStatus fromStatus, MembershipStatus toStatus, OrganizationRole fromRole,
            OrganizationRole toRole, UUID actorUserId, String reason, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.membershipId = membershipId;
        this.organizationId = organizationId;
        this.userId = userId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.fromRole = fromRole;
        this.toRole = toRole;
        this.actorUserId = actorUserId;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public static OrganizationMembershipHistory record(
            OrganizationMembership membership,
            MembershipStatus fromStatus,
            OrganizationRole fromRole,
            UUID actorUserId,
            String reason,
            Instant now) {
        return new OrganizationMembershipHistory(
                membership.getId(), membership.getOrganization().getId(), membership.getUser().getId(),
                fromStatus, membership.getStatus(), fromRole, membership.getRole(), actorUserId, reason, now);
    }

    public UUID getId() { return id; }
    public UUID getMembershipId() { return membershipId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public MembershipStatus getFromStatus() { return fromStatus; }
    public MembershipStatus getToStatus() { return toStatus; }
    public OrganizationRole getFromRole() { return fromRole; }
    public OrganizationRole getToRole() { return toRole; }
    public UUID getActorUserId() { return actorUserId; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}