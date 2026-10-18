package com.pesaguard.backend.sandbox.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.sandbox.domain.Sandbox;
import com.pesaguard.backend.sandbox.domain.SandboxStatus;

public interface SandboxRepository extends JpaRepository<Sandbox, UUID> {

    Optional<Sandbox> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Sandbox> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<Sandbox> findByOrganizationIdAndProjectIdOrderByCreatedAtDesc(
            UUID organizationId, UUID projectId);

    List<Sandbox> findByStatusAndExpiresAtBefore(SandboxStatus status, java.time.Instant cutoff);

    List<Sandbox> findByEnvironmentId(UUID environmentId);
}