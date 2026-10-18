package com.pesaguard.backend.integration.application;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.integration.domain.Integration;
import com.pesaguard.backend.integration.domain.IntegrationCapability;
import com.pesaguard.backend.integration.domain.IntegrationCapabilityType;
import com.pesaguard.backend.integration.infrastructure.IntegrationCapabilityRepository;
import com.pesaguard.backend.integration.infrastructure.IntegrationRepository;

@Service
public class IntegrationProvisioningService {

    private static final Map<IntegrationCapabilityType, Set<String>> REQUIRED_SCOPES = Map.of(
            IntegrationCapabilityType.PAYMENTS, Set.of("payments:read", "payments:write"),
            IntegrationCapabilityType.TRANSACTIONS, Set.of("transactions:read", "transactions:write"),
            IntegrationCapabilityType.MPESA, Set.of("mpesa:read", "mpesa:write"),
            IntegrationCapabilityType.AIRTEL_MONEY, Set.of("airtel:read", "airtel:write"),
            IntegrationCapabilityType.BANKS, Set.of("banks:read", "banks:write"),
            IntegrationCapabilityType.RECONCILIATION, Set.of("reconciliation:read", "reconciliation:write"),
            IntegrationCapabilityType.RISK, Set.of("fraud:read"),
            IntegrationCapabilityType.WEBHOOKS, Set.of("webhooks:read", "webhooks:write"));

    private final IntegrationRepository integrations;
    private final IntegrationCapabilityRepository capabilities;

    public IntegrationProvisioningService(IntegrationRepository integrations,
            IntegrationCapabilityRepository capabilities) {
        this.integrations = integrations;
        this.capabilities = capabilities;
    }

    @Transactional
    public Integration ensureDefault(UUID organizationId, UUID projectId, UUID environmentId,
            EnvironmentType environmentType, Instant now) {
        return integrations.findByEnvironmentIdAndType(environmentId, "PESAGUARD_API")
                .orElseGet(() -> {
                    Integration integration = integrations.saveAndFlush(Integration.createDefault(
                            organizationId, projectId, environmentId, environmentType, now));
                    for (IntegrationCapabilityType capability : IntegrationCapabilityType.values()) {
                        capabilities.save(IntegrationCapability.available(integration.getId(),
                                capability, REQUIRED_SCOPES.get(capability)));
                    }
                    return integration;
                });
    }
}
