package com.pesaguard.backend.sandbox.domain;

import java.util.Objects;
import java.util.UUID;

import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.sandbox.security.SandboxIsolationViolation;

/**
 * The capability token that authorises sandbox execution.
 *
 * <p>This type exists so that "sandbox code accidentally calling production" is
 * not a bug someone has to remember not to write — it is a construct that cannot
 * be written. A sandbox execution path takes a {@code SandboxIsolation}. To reach
 * production it would need a {@link ProductionIsolation}, and there is no method
 * on this class that produces one, converts to one, or accepts an environment id
 * that could be production.
 *
 * <p>The factory is the only way in, and it verifies the environment type. That
 * check is deliberately redundant with the type system on purpose: the compiler
 * proves the call site, and this proves the data. Either alone would be
 * insufficient — the compiler cannot see a UUID fetched from the database, and a
 * runtime check can be forgotten.
 *
 * <p>Instances are immutable and are only ever derived from an environment whose
 * type is {@link EnvironmentType#SANDBOX}.
 */
public final class SandboxIsolation {

    private final UUID sandboxEnvironmentId;
    private final UUID organizationId;
    private final UUID projectId;

    private SandboxIsolation(UUID sandboxEnvironmentId, UUID organizationId, UUID projectId) {
        this.sandboxEnvironmentId = sandboxEnvironmentId;
        this.organizationId = organizationId;
        this.projectId = projectId;
    }

    /**
     * Mints an isolation token for a sandbox environment.
     *
     * @throws SandboxIsolationViolation if the environment is anything other than
     *         SANDBOX — including PRODUCTION
     */
    public static SandboxIsolation forSandboxEnvironment(EnvironmentBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (binding.type() != EnvironmentType.SANDBOX) {
            throw new SandboxIsolationViolation(
                    "Sandbox execution requires a SANDBOX environment, but the target is "
                            + binding.type() + ". Sandbox code must never reach "
                            + binding.type() + ".");
        }
        return new SandboxIsolation(binding.environmentId(), binding.organizationId(), binding.projectId());
    }

    /**
     * Confirms a second environment belongs to the same sandbox.
     *
     * <p>Used when a sandbox execution touches a resource that carries its own
     * environment reference — a credential, a webhook endpoint, an event
     * subscription. A resource from a different environment, even another sandbox,
     * is refused: cross-environment access is how sandbox data leaks into places
     * it should never be read.
     */
    public void requireSameEnvironment(EnvironmentBinding other, String what) {
        Objects.requireNonNull(other, "other");
        if (!sandboxEnvironmentId.equals(other.environmentId())) {
            throw new SandboxIsolationViolation(
                    "Sandbox execution refused: " + what + " belongs to environment "
                            + other.environmentId() + ", which is outside sandbox "
                            + sandboxEnvironmentId + ".");
        }
        if (!organizationId.equals(other.organizationId())) {
            throw new SandboxIsolationViolation(
                    "Sandbox execution refused: " + what + " belongs to a different organization.");
        }
    }

    /**
     * Confirms an environment type may be reached from inside the sandbox.
     *
     * <p>Production is refused unconditionally. This is the invariant the phase
     * exists to guarantee, stated in one place.
     */
    public static void requireNotProduction(EnvironmentType type, String what) {
        Objects.requireNonNull(type, "type");
        if (type == EnvironmentType.PRODUCTION) {
            throw new SandboxIsolationViolation(
                    "Sandbox execution refused: " + what + " targets PRODUCTION. "
                            + "A sandbox must never invoke production functionality.");
        }
    }

    public UUID sandboxEnvironmentId() {
        return sandboxEnvironmentId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID projectId() {
        return projectId;
    }

    @Override
    public String toString() {
        return "SandboxIsolation[environment=" + sandboxEnvironmentId + "]";
    }

    /** The minimal shape of an environment this type needs, so it is testable without a database. */
    public record EnvironmentBinding(UUID environmentId, UUID organizationId, UUID projectId, EnvironmentType type) {
        public EnvironmentBinding {
            Objects.requireNonNull(environmentId, "environmentId");
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(type, "type");
        }
    }
}