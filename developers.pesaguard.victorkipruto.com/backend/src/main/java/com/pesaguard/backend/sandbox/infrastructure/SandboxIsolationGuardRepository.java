package com.pesaguard.backend.sandbox.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.sandbox.domain.SandboxIsolationGuard;

public interface SandboxIsolationGuardRepository
        extends JpaRepository<SandboxIsolationGuard, UUID> {

    /**
     * Execution paths resolve isolation through this. An absent guard means the
     * sandbox has not been pinned, and absence of proof is not proof of isolation,
     * so callers refuse rather than falling back to constructing a token.
     */
    Optional<SandboxIsolationGuard> findBySandboxId(UUID sandboxId);
}