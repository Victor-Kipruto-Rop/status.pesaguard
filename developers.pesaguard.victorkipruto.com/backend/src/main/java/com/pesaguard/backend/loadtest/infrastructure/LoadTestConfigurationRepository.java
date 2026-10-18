package com.pesaguard.backend.loadtest.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.loadtest.domain.LoadTestConfiguration;

public interface LoadTestConfigurationRepository extends JpaRepository<LoadTestConfiguration, UUID> {
    Optional<LoadTestConfiguration> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<LoadTestConfiguration> findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
            UUID id, UUID organizationId, UUID projectId, UUID environmentId);

    List<LoadTestConfiguration> findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
            UUID organizationId, UUID projectId, UUID environmentId);

    void deleteByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
            UUID id, UUID organizationId, UUID projectId, UUID environmentId);
}
