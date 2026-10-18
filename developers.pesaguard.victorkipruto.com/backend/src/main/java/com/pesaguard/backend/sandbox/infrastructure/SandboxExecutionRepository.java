package com.pesaguard.backend.sandbox.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.sandbox.domain.SandboxExecution;

public interface SandboxExecutionRepository extends JpaRepository<SandboxExecution, UUID> {

    List<SandboxExecution> findBySandboxIdOrderByCreatedAtDesc(UUID sandboxId, Pageable pageable);

    List<SandboxExecution> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    long countBySandboxId(UUID sandboxId);

    /**
     * The oldest rows, used to trim history to the sandbox's retention limit.
     * Returned newest-last so a caller can delete the prefix.
     */
    List<SandboxExecution> findBySandboxIdOrderByCreatedAtAsc(UUID sandboxId, Pageable pageable);
}