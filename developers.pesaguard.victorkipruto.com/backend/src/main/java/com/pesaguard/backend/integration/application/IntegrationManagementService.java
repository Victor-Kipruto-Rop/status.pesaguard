package com.pesaguard.backend.integration.application;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.credentials.api.ApiKeyStatus;
import com.pesaguard.backend.environment.api.EnvironmentApiBaseUrls;
import com.pesaguard.backend.environment.application.EnvironmentAccessPolicyService;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.integration.api.IntegrationCapabilityView;
import com.pesaguard.backend.integration.api.IntegrationEventView;
import com.pesaguard.backend.integration.api.IntegrationHealthView;
import com.pesaguard.backend.integration.api.IntegrationTestResultView;
import com.pesaguard.backend.integration.api.IntegrationTestRunView;
import com.pesaguard.backend.integration.api.IntegrationView;
import com.pesaguard.backend.integration.domain.Integration;
import com.pesaguard.backend.integration.infrastructure.IntegrationCapabilityRepository;
import com.pesaguard.backend.integration.infrastructure.IntegrationEventRepository;
import com.pesaguard.backend.integration.infrastructure.IntegrationRepository;
import com.pesaguard.backend.integration.infrastructure.IntegrationTestRunRepository;
import com.pesaguard.backend.project.application.ProjectAuthorization;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class IntegrationManagementService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationManagementService.class);

    private final IntegrationRepository integrations;
    private final IntegrationCapabilityRepository capabilities;
    private final IntegrationTestRunRepository testRuns;
    private final IntegrationEventRepository events;
    private final ApiKeyRepository apiKeys;
    private final ProjectEnvironmentRepository environments;
    private final AuthorizationService authorization;
    private final ProjectAuthorization projectAuthorization;
    private final EnvironmentAccessPolicyService environmentAccess;
    private final IntegrationStateService state;
    private final PesaGuardApiClient apiClient;
    private final Clock clock;

    public IntegrationManagementService(IntegrationRepository integrations,
            IntegrationCapabilityRepository capabilities, IntegrationTestRunRepository testRuns,
            IntegrationEventRepository events,
            ApiKeyRepository apiKeys, ProjectEnvironmentRepository environments, AuthorizationService authorization,
            ProjectAuthorization projectAuthorization, EnvironmentAccessPolicyService environmentAccess,
            IntegrationStateService state,
            PesaGuardApiClient apiClient, Clock clock) {
        this.integrations = integrations;
        this.capabilities = capabilities;
        this.testRuns = testRuns;
        this.events = events;
        this.apiKeys = apiKeys;
        this.environments = environments;
        this.authorization = authorization;
        this.projectAuthorization = projectAuthorization;
        this.environmentAccess = environmentAccess;
        this.state = state;
        this.apiClient = apiClient;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<IntegrationView> list(AuthenticatedUser principal, UUID projectId) {
        authorizeRead(principal, projectId);
        var projectEnvironments = environments.findByOrganizationIdAndProjectIdOrderByCreatedAtAsc(
                principal.organizationId(), projectId);
        var visibleEnvironmentIds = projectEnvironments.stream()
                .filter(environment -> environmentAccess.canAccess(
                        principal, environment, EnvironmentPermission.READ))
                .collect(java.util.stream.Collectors.toSet());
        return integrations.findByOrganizationIdAndProjectIdOrderByCreatedAtDesc(
                        principal.organizationId(), projectId)
                .stream().filter(integration -> visibleEnvironmentIds.contains(integration.getEnvironmentId()))
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public IntegrationView get(AuthenticatedUser principal, UUID projectId, UUID integrationId) {
        return toView(requireReadableIntegration(principal, projectId, integrationId));
    }

    public IntegrationTestResultView test(AuthenticatedUser principal, UUID projectId, UUID integrationId) {
        authorization.requirePermission(principal, Permission.PROJECT_UPDATE);
        projectAuthorization.requireProjectManage(principal, projectId);
        Integration integration = requireIntegration(principal, projectId, integrationId);
        var environment = environments.findByIdAndOrganizationIdAndProjectId(
                        integration.getEnvironmentId(), principal.organizationId(), projectId)
                .filter(item -> item.getStatus() == EnvironmentStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        environmentAccess.requireAccess(principal, environment, EnvironmentPermission.WRITE);

        IntegrationConnectionContext context = state.startTest(principal, integration);
        long started = System.nanoTime();
        PesaGuardApiClient.TestResponse response;
        try {
            response = apiClient.test(EnvironmentApiBaseUrls.forType(environment.getType()),
                    state.decryptSecret(context), environment.getType(), context.requestId());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            response = PesaGuardApiClient.TestResponse.failed("REQUEST_INTERRUPTED",
                    "The connection test was interrupted. Try again.");
        } catch (IOException exception) {
            log.warn("PesaGuard integration request failed integrationId={} requestId={} cause={}",
                    integrationId, context.requestId(), exception.getClass().getSimpleName());
            response = PesaGuardApiClient.TestResponse.failed("API_UNAVAILABLE",
                    "The PesaGuard API could not be reached. Check connectivity and retry.");
        } catch (IllegalStateException exception) {
            log.error("Stored integration credential could not be decrypted integrationId={} requestId={}",
                    integrationId, context.requestId());
            response = PesaGuardApiClient.TestResponse.failed("CREDENTIAL_UNAVAILABLE",
                    "The stored API credential is unavailable. Create a new API key and retry.");
        }
        long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        var run = state.finishTest(principal, context, response, latencyMs);
        Integration updated = requireIntegration(principal, projectId, integrationId);
        return new IntegrationTestResultView(run.getStatus().name(), updated.getStatus().name(),
                updated.getHealthStatus().name(), run.getRequestId(),
                run.getLatencyMs() == null ? latencyMs : run.getLatencyMs(),
                run.getFailureCategory(), run.getSafeMessage());
    }

    @Transactional(readOnly = true)
    public IntegrationHealthView health(AuthenticatedUser principal, UUID projectId, UUID integrationId) {
        Integration integration = requireReadableIntegration(principal, projectId, integrationId);
        return new IntegrationHealthView(integration.getId(), integration.getStatus().name(),
                integration.getHealthStatus().name(), integration.getLastTestedAt(),
                integration.getLastSuccessAt(), integration.getLastFailureAt(),
                integration.getLastRequestId());
    }

    @Transactional(readOnly = true)
    public List<IntegrationTestRunView> tests(
            AuthenticatedUser principal, UUID projectId, UUID integrationId) {
        requireReadableIntegration(principal, projectId, integrationId);
        return testRuns.findTop100ByIntegrationIdOrderByStartedAtDesc(integrationId)
                .stream().map(IntegrationTestRunView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<IntegrationEventView> events(
            AuthenticatedUser principal, UUID projectId, UUID integrationId) {
        requireReadableIntegration(principal, projectId, integrationId);
        return events.findTop100ByOrganizationIdAndIntegrationIdOrderByCreatedAtDesc(
                        principal.organizationId(), integrationId)
                .stream().map(IntegrationEventView::from).toList();
    }

    public void setEnabled(AuthenticatedUser principal, UUID projectId, UUID integrationId, boolean enabled) {
        authorization.requirePermission(principal, Permission.PROJECT_UPDATE);
        projectAuthorization.requireProjectManage(principal, projectId);
        Integration integration = requireIntegration(principal, projectId, integrationId);
        var environment = environments.findByIdAndOrganizationIdAndProjectId(
                        integration.getEnvironmentId(), principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        environmentAccess.requireAccess(principal, environment, EnvironmentPermission.WRITE);
        state.setEnabled(principal, principal.organizationId(), projectId, integrationId, enabled);
    }

    private Integration requireReadableIntegration(
            AuthenticatedUser principal, UUID projectId, UUID integrationId) {
        authorizeRead(principal, projectId);
        Integration integration = requireIntegration(principal, projectId, integrationId);
        var environment = environments.findByIdAndOrganizationIdAndProjectId(
                        integration.getEnvironmentId(), principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        environmentAccess.requireAccess(principal, environment, EnvironmentPermission.READ);
        return integration;
    }

    private Integration requireIntegration(AuthenticatedUser principal, UUID projectId, UUID integrationId) {
        return integrations.findByIdAndOrganizationIdAndProjectId(
                        integrationId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Integration"));
    }

    private void authorizeRead(AuthenticatedUser principal, UUID projectId) {
        authorization.requirePermission(principal, Permission.PROJECT_READ);
        projectAuthorization.requireProjectRead(principal, projectId);
    }

    private IntegrationView toView(Integration integration) {
        String status = integration.getStatus().name();
        if (integration.getStatus() == com.pesaguard.backend.integration.domain.IntegrationStatus.NOT_CONFIGURED
                && integration.isEnabled()
                && apiKeys.findByOrganizationIdAndProjectIdAndEnvironmentIdAndStatusOrderByCreatedAtDesc(
                        integration.getOrganizationId(), integration.getProjectId(),
                        integration.getEnvironmentId(), ApiKeyStatus.ACTIVE).stream()
                        .anyMatch(key -> key.getEncryptedSecret() != null
                                && key.scopeSet().contains("api:read")
                                && (key.getExpiresAt() == null || key.getExpiresAt().isAfter(clock.instant())))) {
            status = com.pesaguard.backend.integration.domain.IntegrationStatus.READY_TO_TEST.name();
        }
        return IntegrationView.from(integration, capabilities.findByIntegrationIdOrderByCapabilityAsc(
                        integration.getId()).stream()
                .map(IntegrationCapabilityView::from).toList(), status);
    }
}
