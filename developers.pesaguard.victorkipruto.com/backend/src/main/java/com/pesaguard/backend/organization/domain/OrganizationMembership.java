package com.pesaguard.backend.organization.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.pesaguard.backend.member.domain.UserAccount;

@Entity
@Table(name = "organization_memberships")
public class OrganizationMembership {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 24)
    private OrganizationRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private MembershipStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected OrganizationMembership() {
    }

    private OrganizationMembership(UUID id, Organization organization, UserAccount user, OrganizationRole role) {
        this.id = id;
        this.organization = organization;
        this.user = user;
        this.role = role;
        this.status = MembershipStatus.ACTIVE;
    }

    public static OrganizationMembership owner(Organization organization, UserAccount user) {
        return new OrganizationMembership(UUID.randomUUID(), organization, user, OrganizationRole.OWNER);
    }

    public static OrganizationMembership member(Organization organization, UserAccount user, OrganizationRole role) {
        if (role == null || role == OrganizationRole.OWNER) {
            throw new IllegalArgumentException("A non-owner role is required");
        }
        return new OrganizationMembership(UUID.randomUUID(), organization, user, role);
    }

    public void makeOwner() {
        this.role = OrganizationRole.OWNER;
        this.status = MembershipStatus.ACTIVE;
    }

    public void relinquishOwnership() {
        if (role != OrganizationRole.OWNER) {
            throw new IllegalStateException("Membership is not the organization owner");
        }
        this.role = OrganizationRole.ADMIN;
    }

    public void changeRole(OrganizationRole newRole) {
        if (newRole == null || newRole == OrganizationRole.OWNER) {
            throw new IllegalArgumentException("Ownership changes require an explicit transfer");
        }
        if (role == OrganizationRole.OWNER) {
            throw new IllegalStateException("Transfer ownership before changing the owner role");
        }
        this.role = newRole;
    }

    public void suspend() {
        if (role == OrganizationRole.OWNER) {
            throw new IllegalStateException("The organization owner cannot be suspended");
        }
        this.status = MembershipStatus.SUSPENDED;
    }

    public void activate() {
        this.status = MembershipStatus.ACTIVE;
    }

    public void remove() {
        if (role == OrganizationRole.OWNER) {
            throw new IllegalStateException("The organization owner cannot be removed");
        }
        this.status = MembershipStatus.REVOKED;
    }

    public UUID getId() {
        return id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public UserAccount getUser() {
        return user;
    }

    public OrganizationRole getRole() {
        return role;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE && user.getStatus().canAuthenticate();
    }
}
