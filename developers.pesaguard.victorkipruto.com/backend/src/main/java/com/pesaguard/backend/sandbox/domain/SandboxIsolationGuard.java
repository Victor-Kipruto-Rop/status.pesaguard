package com.pesaguard.backend.sandbox.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.pesaguard.backend.environment.domain.EnvironmentType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The row that pins a sandbox to one environment, recorded as data.
 *
 * <p>This is the database's half of the isolation guarantee. The code half is
 * {@link SandboxIsolation}, which cannot be minted for a non-sandbox environment.
 * This table means that even a writer which never loads the application code — a
 * migration, a psql session, a future service in another language — cannot record
 * a sandbox against production: the check constraint rejects the row.
 *
 * <p>{@code environmentType} is therefore always {@code SANDBOX}. It is stored
 * rather than inferred so the guarantee is a positive assertion that can be
 * queried, audited and alerted on, not an absence of evidence.
 */
@Entity
@Table(name = "sandbox_isolation_guards")
public class SandboxIsolationGuard {

    @Id
    @Column(name = "sandbox_id", nullable = false)
    private UUID sandboxId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment_type", nullable = false, length = 24)
    private EnvironmentType environmentType;

    @CreationTimestamp
    @Column(name = "pinned_at", nullable = false, updatable = false)
    private Instant pinnedAt;

    protected SandboxIsolationGuard() {
    }

    private SandboxIsolationGuard(Sandbox sandbox) {
        this.sandboxId = sandbox.getId();
        this.organizationId = sandbox.getOrganizationId();
        this.projectId = sandbox.getProjectId();
        this.environmentId = sandbox.getEnvironmentId();
        this.environmentType = EnvironmentType.SANDBOX;
    }

    /**
     * Pins the sandbox.
     *
     * <p>Takes the {@link Sandbox}, not a free-form environment id, so the row
     * cannot name an environment the sandbox does not own.
     */
    public static SandboxIsolationGuard pin(Sandbox sandbox) {
        return new SandboxIsolationGuard(sandbox);
    }

    /** The isolation token this guard corresponds to. */
    public SandboxIsolation toIsolation() {
        return SandboxIsolation.forSandboxEnvironment(new SandboxIsolation.EnvironmentBinding(
                environmentId, organizationId, projectId, EnvironmentType.SANDBOX));
    }

    public UUID getSandboxId() { return sandboxId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public EnvironmentType getEnvironmentType() { return environmentType; }
    public Instant getPinnedAt() { return pinnedAt; }
}