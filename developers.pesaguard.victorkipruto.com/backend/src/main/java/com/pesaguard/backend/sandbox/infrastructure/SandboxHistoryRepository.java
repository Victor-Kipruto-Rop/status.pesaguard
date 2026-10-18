package com.pesaguard.backend.sandbox.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.sandbox.domain.SandboxHistory;

public interface SandboxHistoryRepository extends JpaRepository<SandboxHistory, UUID> {

    List<SandboxHistory> findBySandboxIdOrderByCreatedAtDesc(UUID sandboxId, Pageable pageable);

    List<SandboxHistory> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);
}