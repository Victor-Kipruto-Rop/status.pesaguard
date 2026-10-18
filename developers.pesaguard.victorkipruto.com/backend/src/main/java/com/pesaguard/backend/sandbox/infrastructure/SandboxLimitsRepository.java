package com.pesaguard.backend.sandbox.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.sandbox.domain.SandboxLimits;

public interface SandboxLimitsRepository extends JpaRepository<SandboxLimits, UUID> {

    Optional<SandboxLimits> findBySandboxId(UUID sandboxId);
}