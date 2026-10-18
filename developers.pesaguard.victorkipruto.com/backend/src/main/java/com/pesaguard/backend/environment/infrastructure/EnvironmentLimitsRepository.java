package com.pesaguard.backend.environment.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.environment.domain.EnvironmentLimits;

public interface EnvironmentLimitsRepository extends JpaRepository<EnvironmentLimits, UUID> {

    Optional<EnvironmentLimits> findByEnvironmentId(UUID environmentId);
}