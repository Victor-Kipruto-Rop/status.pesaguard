package com.pesaguard.backend.loadtest.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LoadTestConfigurationTest {
    @Test
    void updateReplacesConfigurationWithoutChangingItsScopeOrIdentity() {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        LoadTestConfiguration configuration = LoadTestConfiguration.create(
                organizationId, projectId, environmentId, creatorId,
                "Original", null, "/api/v1/status", "GET", "application/json",
                Map.of(), null, null, LoadPattern.SMOKE, 5, null, null, 60,
                List.of(new LoadTestStage(60, 5)), List.of());
        List<LoadTestStage> updatedStages = List.of(new LoadTestStage(30, 10));
        List<LoadTestThreshold> updatedThresholds = List.of(new LoadTestThreshold("p95Ms", "<=", 250));

        configuration.update(" Updated ", " description ", "/api/v1/projects", "GET",
                "application/json", Map.of("Accept", "application/json"), null, null,
                LoadPattern.RAMP, 10, null, null, 30, updatedStages, updatedThresholds);

        assertEquals(organizationId, configuration.getOrganizationId());
        assertEquals(projectId, configuration.getProjectId());
        assertEquals(environmentId, configuration.getEnvironmentId());
        assertEquals(creatorId, configuration.getCreatedBy());
        assertEquals("Updated", configuration.getName());
        assertEquals("description", configuration.getDescription());
        assertEquals("/api/v1/projects", configuration.getEndpointPath());
        assertEquals(LoadPattern.RAMP, configuration.getLoadPattern());
        assertEquals(10, configuration.getTargetVus());
        assertEquals(updatedStages, configuration.getStages());
        assertEquals(updatedThresholds, configuration.getThresholds());
    }
}
