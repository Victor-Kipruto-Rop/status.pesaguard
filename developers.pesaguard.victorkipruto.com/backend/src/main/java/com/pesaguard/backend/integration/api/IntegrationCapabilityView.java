package com.pesaguard.backend.integration.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.pesaguard.backend.integration.domain.IntegrationCapability;

public record IntegrationCapabilityView(
        UUID id,
        String capability,
        String status,
        Set<String> requiredScopes,
        boolean enabled,
        Instant lastVerifiedAt) {

    public static IntegrationCapabilityView from(IntegrationCapability capability) {
        return new IntegrationCapabilityView(capability.getId(), capability.getCapability().name(),
                capability.getStatus().name(), capability.getRequiredScopes(),
                capability.isEnabled(), capability.getLastVerifiedAt());
    }
}
