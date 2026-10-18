package com.pesaguard.backend.sandbox.domain;

import java.time.Duration;
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

/**
 * A sandbox: an isolated environment in which integrations can be exercised
 * without touching production.
 *
 * <p>The lifecycle is explicit rather than implied by configuration, because
 * "is this sandbox live?" must be answerable from a single row without joining
 * anything or inferring from timestamps.
 *
 * <p>Two rules are enforced in the entity rather than the service, because they
 * are invariants of the thing itself and must hold however it is reached:
 *
 * <ul>
 *   <li>Expiry never resurrects. {@link #effectiveStatus} reports {@code EXPIRED}
 *       once the deadline passes even if the stored status still says ACTIVE, so
 *       a sandbox whose expiry sweep has not run is already inert.</li>
 *   <li>Deletion is terminal. There is no transition out of {@code DELETED}.</li>
 * </ul>
 */
@Entity
@Table(name = "sandboxes")
public class Sandbox {

    public static final Duration DEFAULT_TTL = Duration.ofDays(30);
    public static final Duration MAX_TTL = Duration.ofDays(90);
    public static final Duration MIN_TTL = Duration.ofHours(1);

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** The SANDBOX environment this sandbox executes inside. */
    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SandboxStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "last_reset_at")
    private Instant lastResetAt;

    @Column(name = "reset_count", nullable = false)
    private int resetCount;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Sandbox() {
    }

    private Sandbox(UUID organizationId, UUID projectId, UUID environmentId, String name,
            String description, Instant expiresAt, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.name = name == null || name.isBlank() ? "sandbox" : name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.status = SandboxStatus.PROVISIONING;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
        this.resetCount = 0;
    }

    public static Sandbox create(UUID organizationId, UUID projectId, UUID environmentId,
            String name, String description, Duration ttl, UUID createdBy, Instant now) {
        Duration effective = ttl == null ? DEFAULT_TTL : ttl;
        if (effective.compareTo(MIN_TTL) < 0 || effective.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("Sandbox TTL must be between "
                    + MIN_TTL.toHours() + " hours and " + MAX_TTL.toDays() + " days");
        }
        return new Sandbox(organizationId, projectId, environmentId, name, description,
                now.plus(effective), createdBy);
    }

/** PROVISIONING -> ACTIVE. The only transition that enables execution. */
    public void activate(Instant now) {
        if (status != SandboxStatus.PROVISIONING) {
            throw new IllegalStateException("Only a provisioning sandbox can be activated");
        }
        if (!now.isBefore(expiresAt)) {
            throw new IllegalStateException("A sandbox cannot be activated after it has expired");
        }
        status = SandboxStatus.ACTIVE;
        activatedAt = now;
    }

    /** ACTIVE -> SUSPENDED. Data is retained so the sandbox can be resumed. */
    public void suspend(Instant now) {
        if (status == SandboxStatus.DELETED) {
            throw new IllegalStateException("A deleted sandbox cannot be suspended");
        }
        if (status == SandboxStatus.SUSPENDED) {
            return;
        }
        status = SandboxStatus.SUSPENDED;
        suspendedAt = now;
    }

    /** SUSPENDED -> ACTIVE. An expired sandbox cannot be resumed. */
    public void resume(Instant now) {
        if (status != SandboxStatus.SUSPENDED) {
            throw new IllegalStateException("Only a suspended sandbox can be resumed");
        }
        if (!now.isBefore(expiresAt)) {
            // Resuming past expiry would silently extend a sandbox nobody decided to
            // keep. Deleting and creating a fresh one is the deliberate action.
            status = SandboxStatus.EXPIRED;
            throw new IllegalStateException("An expired sandbox cannot be resumed");
        }
        status = SandboxStatus.ACTIVE;
        suspendedAt = null;
    }

    /**
     * Marks the sandbox expired. Idempotent, because an expiry sweep may run more
     * than once and a second run must not throw.
     */
    public void expire(Instant now) {
        if (status == SandboxStatus.DELETED || status == SandboxStatus.EXPIRED) {
            return;
        }
        status = SandboxStatus.EXPIRED;
        suspendedAt = null;
    }

    /**
     * Records a reset. Reset clears sandbox data and reseeds fixtures; it does not
     * change lifecycle status, so a suspended sandbox stays suspended.
     */
    public void recordReset(Instant now) {
        if (status == SandboxStatus.DELETED) {
            throw new IllegalStateException("A deleted sandbox cannot be reset");
        }
        this.lastResetAt = now;
        this.resetCount++;
    }

    /** Terminal. There is no transition out of DELETED. */
    public void delete(Instant now) {
        if (status == SandboxStatus.DELETED) {
            return;
        }
        status = SandboxStatus.DELETED;
        deletedAt = now;
        suspendedAt = null;
    }

    /**
     * The status as of now, accounting for expiry that has passed but not yet been
     * swept.
     *
     * <p>This is what execution paths must consult. Reading {@code status} directly
     * would let a sandbox whose expiry sweep has not run yet keep working.
     */
    public SandboxStatus effectiveStatus(Instant now) {
        if (status == SandboxStatus.DELETED) {
            return SandboxStatus.DELETED;
        }
        if (!now.isBefore(expiresAt) && status != SandboxStatus.EXPIRED) {
            return SandboxStatus.EXPIRED;
        }
        return status;
    }

    public boolean canExecute(Instant now) {
        return effectiveStatus(now).allowsExecution();
    }

    public boolean isExpired(Instant now) {
        return effectiveStatus(now) == SandboxStatus.EXPIRED;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public SandboxStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getSuspendedAt() { return suspendedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public Instant getLastResetAt() { return lastResetAt; }
    public int getResetCount() { return resetCount; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
