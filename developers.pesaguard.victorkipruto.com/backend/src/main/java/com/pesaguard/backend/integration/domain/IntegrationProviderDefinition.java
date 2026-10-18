package com.pesaguard.backend.integration.domain;

public record IntegrationProviderDefinition(
        String providerId,
        String displayName,
        String category,
        String description,
        IntegrationAvailability availability,
        boolean connectionApiAvailable) {
}
