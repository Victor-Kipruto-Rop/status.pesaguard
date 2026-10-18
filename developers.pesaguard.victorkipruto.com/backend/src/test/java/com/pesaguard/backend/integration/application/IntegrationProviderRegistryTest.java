package com.pesaguard.backend.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.integration.domain.IntegrationAvailability;

class IntegrationProviderRegistryTest {

    private final IntegrationProviderRegistry registry = new IntegrationProviderRegistry();

    @Test
    void publishesEveryRequestedIntegrationWithExplicitAvailability() {
        assertThat(registry.providers())
                .extracting(provider -> provider.providerId())
                .containsExactly(
                        "tenant_dashboard",
                        "pesaguard_application",
                        "api",
                        "webhook",
                        "sdk",
                        "mcp",
                        "cicd",
                        "github",
                        "monitoring",
                        "logging",
                        "siem");
        assertThat(registry.providers()).allSatisfy(provider ->
                assertThat(provider.connectionApiAvailable()).isFalse());
    }

    @Test
    void reportsExternalProvidersThatAreNotConfiguredSeparatelyFromUnavailableOnes() {
        assertThat(registry.find("github").availability()).isEqualTo(IntegrationAvailability.NOT_CONFIGURED);
        assertThat(registry.find("mcp").availability()).isEqualTo(IntegrationAvailability.UNAVAILABLE);
        assertThat(registry.find("api").availability()).isEqualTo(IntegrationAvailability.AVAILABLE);
    }

    @Test
    void unknownProvidersAreNotResolved() {
        assertThat(registry.find("unknown-provider")).isNull();
    }
}
