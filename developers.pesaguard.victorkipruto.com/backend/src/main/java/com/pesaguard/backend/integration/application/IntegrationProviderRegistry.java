package com.pesaguard.backend.integration.application;

import java.util.List;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.integration.domain.IntegrationAvailability;
import com.pesaguard.backend.integration.domain.IntegrationProviderDefinition;

@Component
public class IntegrationProviderRegistry {

    private static final boolean CONNECTION_API_AVAILABLE = false;

    private final List<IntegrationProviderDefinition> providers = List.of(
            provider("tenant_dashboard", "Tenant dashboard", "PesaGuard Platform",
                    "Manage organizations, members, projects, and tenant-level controls.",
                    IntegrationAvailability.AVAILABLE),
            provider("pesaguard_application", "PesaGuard application", "PesaGuard Platform",
                    "Register application clients and manage service identities in the PesaGuard platform.",
                    IntegrationAvailability.AVAILABLE),
            provider("api", "API integration", "PesaGuard Platform",
                    "Explore API endpoints, authentication requirements, and request examples.",
                    IntegrationAvailability.AVAILABLE),
            provider("webhook", "Webhook integration", "PesaGuard Platform",
                    "Manage event destinations, delivery attempts, and webhook signing controls.",
                    IntegrationAvailability.AVAILABLE),
            provider("sdk", "SDK integration", "Developer Tooling",
                    "Browse the SDK and developer tooling capabilities currently published by PesaGuard.",
                    IntegrationAvailability.AVAILABLE),
            provider("mcp", "MCP integration", "Developer Tooling",
                    "MCP provider authorization and scoped tool connections are not configured.",
                    IntegrationAvailability.UNAVAILABLE),
            provider("cicd", "CI/CD integration", "Developer Tooling",
                    "No CI/CD provider authorization or deployment connection is configured.",
                    IntegrationAvailability.NOT_CONFIGURED),
            provider("github", "GitHub integration", "Developer Tooling",
                    "GitHub App authorization and repository access are not configured.",
                    IntegrationAvailability.NOT_CONFIGURED),
            provider("monitoring", "Monitoring integrations", "Observability",
                    "Built-in usage analytics are available; external monitoring-provider delivery is not configured.",
                    IntegrationAvailability.AVAILABLE),
            provider("logging", "Logging integrations", "Observability",
                    "Platform logs are available; external log forwarding is not configured.",
                    IntegrationAvailability.AVAILABLE),
            provider("siem", "SIEM integrations", "Observability",
                    "External SIEM authorization and security-event export are not configured.",
                    IntegrationAvailability.NOT_CONFIGURED));

    public List<IntegrationProviderDefinition> providers() {
        return providers;
    }

    public IntegrationProviderDefinition find(String providerId) {
        return providers.stream()
                .filter(provider -> provider.providerId().equals(providerId))
                .findFirst()
                .orElse(null);
    }

    private static IntegrationProviderDefinition provider(
            String providerId,
            String displayName,
            String category,
            String description,
            IntegrationAvailability availability) {
        return new IntegrationProviderDefinition(
                providerId,
                displayName,
                category,
                description,
                availability,
                CONNECTION_API_AVAILABLE);
    }
}
