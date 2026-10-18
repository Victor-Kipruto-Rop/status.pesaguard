package com.pesaguard.backend.integration.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.credentials.api.ApiKeyStatus;
import com.pesaguard.backend.integration.domain.Integration;
import com.pesaguard.backend.integration.domain.IntegrationEvent;
import com.pesaguard.backend.integration.domain.IntegrationTestRun;
import com.pesaguard.backend.integration.domain.IntegrationTestStatus;
import com.pesaguard.backend.integration.infrastructure.IntegrationCapabilityRepository;
import com.pesaguard.backend.integration.infrastructure.IntegrationEventRepository;
import com.pesaguard.backend.integration.infrastructure.IntegrationRepository;
import com.pesaguard.backend.integration.infrastructure.IntegrationTestRunRepository;
import com.pesaguard.backend.security.credentials.SecretEncryptionService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class IntegrationStateService {

    private final IntegrationRepository integrations;
    private final IntegrationCapabilityRepository capabilities;
    private final IntegrationTestRunRepository testRuns;
    private final IntegrationEventRepository events;
    private final ApiKeyRepository apiKeys;
    private final SecretEncryptionService encryption;
    private final AuditService audit;
    private final Clock clock;

    public IntegrationStateService(IntegrationRepository integrations,
            IntegrationCapabilityRepository capabilities, IntegrationTestRunRepository testRuns,
            IntegrationEventRepository events, ApiKeyRepository apiKeys,
            SecretEncryptionService encryption, AuditService audit, Clock clock) {
        this.integrations = integrations;
        this.capabilities = capabilities;
        this.testRuns = testRuns;
        this.events = events;
        this.apiKeys = apiKeys;
        this.encryption = encryption;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IntegrationConnectionContext startTest(AuthenticatedUser principal, Integration integration) {
        Integration locked = integrations.findByIdAndOrganizationIdAndProjectIdForUpdate(
                integration.getId(), principal.organizationId(), integration.getProjectId())
                .orElseThrow(() -> new com.pesaguard.backend.common.exception.ResourceNotFoundException("Integration"));
        if (!locked.isEnabled()) {
            throw new BusinessException(HttpStatus.CONFLICT, "INTEGRATION_DISABLED",
                    "Enable the integration before testing its connection.");
        }
        if (locked.getStatus() == com.pesaguard.backend.integration.domain.IntegrationStatus.TESTING) {
            throw new BusinessException(HttpStatus.CONFLICT, "INTEGRATION_TEST_IN_PROGRESS",
                    "A connection test is already in progress.");
        }
        Instant now = clock.instant();
        var key = apiKeys.findByOrganizationIdAndProjectIdAndEnvironmentIdAndStatusOrderByCreatedAtDesc(
                        principal.organizationId(), locked.getProjectId(), locked.getEnvironmentId(), ApiKeyStatus.ACTIVE)
                .stream()
                .filter(candidate -> candidate.getExpiresAt() == null || candidate.getExpiresAt().isAfter(now))
                .filter(candidate -> candidate.getEncryptedSecret() != null)
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                        "INTEGRATION_API_KEY_REQUIRED",
                        "Create an active API key for this environment before testing the integration."));
        UUID requestId = RequestContext.currentRequestId();
        locked.startTest(requestId);
        integrations.save(locked);
        IntegrationTestRun run = testRuns.saveAndFlush(
                IntegrationTestRun.start(locked.getId(), requestId, now));
        return new IntegrationConnectionContext(locked.getId(), locked.getOrganizationId(),
                locked.getProjectId(), locked.getEnvironmentId(), locked.getEnvironmentType(),
                key.getEncryptedSecret(), key.scopeSet(), run.getId(), requestId);
    }

    @Transactional
    public IntegrationTestRun finishTest(AuthenticatedUser principal,
            IntegrationConnectionContext context, PesaGuardApiClient.TestResponse response,
            long latencyMs) {
        Instant completedAt = clock.instant();
        Integration integration = integrations.findByIdAndOrganizationIdAndProjectIdForUpdate(
                context.integrationId(), context.organizationId(), context.projectId())
                .orElseThrow(() -> new com.pesaguard.backend.common.exception.ResourceNotFoundException("Integration"));
        IntegrationTestRun run = testRuns.findById(context.testRunId())
                .orElseThrow(() -> new com.pesaguard.backend.common.exception.ResourceNotFoundException(
                        "Integration test"));
        if (run.getStatus() != IntegrationTestStatus.RUNNING) {
            throw new BusinessException(HttpStatus.CONFLICT, "INTEGRATION_TEST_ALREADY_FINISHED",
                    "This connection test has already finished.");
        }
        run.complete(response.success(), latencyMs, response.failureCategory(), response.message(), completedAt);
        integration.recordTest(response.success(), completedAt, context.requestId(), null);
        var integrationCapabilities = capabilities.findByIntegrationIdOrderByCapabilityAsc(integration.getId());
        if (response.success()) {
            for (var capability : integrationCapabilities) {
                capability.verifyScopes(context.scopes(), completedAt);
            }
        }
        testRuns.save(run);
        integrations.save(integration);
        capabilities.saveAll(integrationCapabilities);
        String eventType = response.success() ? "CONNECTION_TEST_SUCCEEDED" : "CONNECTION_TEST_FAILED";
        events.save(IntegrationEvent.record(context.organizationId(), integration.getId(),
                principal.userId(), eventType, context.requestId(),
                response.success() ? "Connection and environment verified."
                        : response.failureCategory()));
        String action = response.success()
                ? "integration.connection_test_succeeded"
                : "integration.connection_test_failed";
        audit.append(context.organizationId(), principal.userId(), action,
                "integration", integration.getId().toString(), context.requestId(),
                Map.of("failureCategory", response.failureCategory() == null ? "none"
                        : response.failureCategory()));
        return run;
    }

    public String decryptSecret(IntegrationConnectionContext context) {
        return encryption.decrypt(context.encryptedSecret());
    }

    @Transactional
    public void setEnabled(AuthenticatedUser principal, UUID organizationId, UUID projectId,
            UUID integrationId, boolean enabled) {
        Integration integration = integrations.findByIdAndOrganizationIdAndProjectIdForUpdate(
                integrationId, organizationId, projectId)
                .orElseThrow(() -> new com.pesaguard.backend.common.exception.ResourceNotFoundException("Integration"));
        if (enabled) integration.enable();
        else integration.disable();
        integrations.save(integration);
        UUID requestId = RequestContext.currentRequestId();
        String eventType = enabled ? "INTEGRATION_ENABLED" : "INTEGRATION_DISABLED";
        String action = enabled ? "integration.enabled" : "integration.disabled";
        events.save(IntegrationEvent.record(organizationId, integrationId, principal.userId(),
                eventType, requestId, ""));
        audit.append(organizationId, principal.userId(), action, "integration",
                integrationId.toString(), requestId, Map.of("enabled", enabled));
    }
}
