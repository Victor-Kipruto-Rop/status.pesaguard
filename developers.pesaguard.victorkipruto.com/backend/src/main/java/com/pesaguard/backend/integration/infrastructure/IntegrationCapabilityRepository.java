package com.pesaguard.backend.integration.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.integration.domain.IntegrationCapability;

public interface IntegrationCapabilityRepository extends JpaRepository<IntegrationCapability, UUID> {

    List<IntegrationCapability> findByIntegrationIdOrderByCapabilityAsc(UUID integrationId);
}
