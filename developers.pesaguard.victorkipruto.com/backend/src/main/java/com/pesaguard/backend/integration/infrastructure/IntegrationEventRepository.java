package com.pesaguard.backend.integration.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.integration.domain.IntegrationEvent;

public interface IntegrationEventRepository extends JpaRepository<IntegrationEvent, UUID> {

    List<IntegrationEvent> findTop100ByOrganizationIdAndIntegrationIdOrderByCreatedAtDesc(
            UUID organizationId, UUID integrationId);
}
