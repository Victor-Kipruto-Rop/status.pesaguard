package com.pesaguard.backend.integration.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pesaguard.backend.integration.domain.Integration;

public record IntegrationView(
        UUID id,
        UUID projectId,
        UUID environmentId,
        String environmentType,
        String type,
        String provider,
        String status,
        String healthStatus,
        String displayName,
        String description,
        boolean enabled,
        Instant lastTestedAt,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        UUID lastRequestId,
        String lastTraceId,
        List<IntegrationCapabilityView> capabilities,
        Instant createdAt,
        Instant updatedAt) {

    public static IntegrationView from(Integration integration,
            List<IntegrationCapabilityView> capabilities) {
        return from(integration, capabilities, integration.getStatus().name());
    }

    public static IntegrationView from(Integration integration,
            List<IntegrationCapabilityView> capabilities, String effectiveStatus) {
        return new IntegrationView(integration.getId(), integration.getProjectId(),
                integration.getEnvironmentId(), integration.getEnvironmentType().name(),
                integration.getType(), integration.getProvider(), effectiveStatus,
                integration.getHealthStatus().name(), integration.getDisplayName(),
                integration.getDescription(), integration.isEnabled(), integration.getLastTestedAt(),
                integration.getLastSuccessAt(), integration.getLastFailureAt(),
                integration.getLastRequestId(), integration.getLastTraceId(),
                List.copyOf(capabilities), integration.getCreatedAt(), integration.getUpdatedAt());
    }
}
