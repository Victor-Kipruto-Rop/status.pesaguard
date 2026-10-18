package com.pesaguard.backend.organization.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 80)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OrganizationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "organization_type", nullable = false, length = 32)
    private OrganizationType organizationType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verification_reference", length = 120)
    private String verificationReference;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "audit_sequence", nullable = false)
    private long auditSequence;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Organization() {
    }

    private Organization(UUID id, String name, String slug, UUID ownerUserId, Instant now) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.ownerUserId = ownerUserId;
        this.status = OrganizationStatus.ACTIVE;
        this.organizationType = OrganizationType.DEVELOPER;
        this.metadata = new LinkedHashMap<>();
        this.statusChangedAt = now;
        this.auditSequence = 0;
    }

    public static Organization create(String name, String slug, UUID ownerUserId, Instant now) {
        return new Organization(UUID.randomUUID(), name, slug, ownerUserId, now);
    }

    public long nextAuditSequence() {
        return ++auditSequence;
    }

    public UUID getId() {
        return id;
    }

    public void update(String name, OrganizationType organizationType, Map<String, Object> metadata) {
        this.name = name.trim();
        this.organizationType = organizationType;
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    public void verify(String reference, Instant now) {
        if (status == OrganizationStatus.DELETED || status == OrganizationStatus.DISABLED) {
            throw new IllegalStateException("Organization cannot be verified in its current state");
        }
        this.status = OrganizationStatus.ACTIVE;
        this.verifiedAt = now;
        this.verificationReference = reference;
        this.statusChangedAt = now;
        this.deletedAt = null;
    }

    public void suspend(Instant now) {
        requireNotDeleted();
        this.status = OrganizationStatus.SUSPENDED;
        this.statusChangedAt = now;
    }

    public void markPending(Instant now) {
        requireNotDeleted();
        this.status = OrganizationStatus.PENDING;
        this.statusChangedAt = now;
    }

    public void restore(Instant now) {
        if (status == OrganizationStatus.DELETED) {
            throw new IllegalStateException("Deleted organizations cannot be restored");
        }
        this.status = OrganizationStatus.ACTIVE;
        this.statusChangedAt = now;
    }

    public void disable(Instant now) {
        requireNotDeleted();
        this.status = OrganizationStatus.DISABLED;
        this.statusChangedAt = now;
    }

    public void delete(Instant now) {
        if (status == OrganizationStatus.DELETED) {
            return;
        }
        this.status = OrganizationStatus.DELETED;
        this.statusChangedAt = now;
        this.deletedAt = now;
    }

    public void transferOwnership(UUID newOwnerUserId) {
        if (status == OrganizationStatus.DELETED) {
            throw new IllegalStateException("Deleted organizations cannot transfer ownership");
        }
        this.ownerUserId = newOwnerUserId;
    }

    private void requireNotDeleted() {
        if (status == OrganizationStatus.DELETED) {
            throw new IllegalStateException("Deleted organizations cannot change lifecycle state");
        }
    }

    public OrganizationType getOrganizationType() {
        return organizationType;
    }

    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public String getVerificationReference() {
        return verificationReference;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public boolean isDeleted() {
        return status == OrganizationStatus.DELETED;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public OrganizationStatus getStatus() {
        return status;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public boolean isActive() {
        return status == OrganizationStatus.ACTIVE;
    }
}
