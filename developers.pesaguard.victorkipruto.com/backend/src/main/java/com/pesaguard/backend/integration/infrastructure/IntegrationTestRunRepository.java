package com.pesaguard.backend.integration.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.integration.domain.IntegrationTestRun;

public interface IntegrationTestRunRepository extends JpaRepository<IntegrationTestRun, UUID> {

    List<IntegrationTestRun> findTop100ByIntegrationIdOrderByStartedAtDesc(UUID integrationId);
}
