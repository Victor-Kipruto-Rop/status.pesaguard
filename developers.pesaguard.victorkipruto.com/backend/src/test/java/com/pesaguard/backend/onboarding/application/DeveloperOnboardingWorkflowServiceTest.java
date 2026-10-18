package com.pesaguard.backend.onboarding.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.analytics.infrastructure.ApiRequestEventRepository;
import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.integration.infrastructure.IntegrationRepository;
import com.pesaguard.backend.member.infrastructure.MfaSecretRepository;
import com.pesaguard.backend.onboarding.infrastructure.DeveloperOnboardingProgressRepository;
import com.pesaguard.backend.onboarding.infrastructure.DeveloperOnboardingStepRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.rbac.infrastructure.ProductionAccessRequestRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.webhooks.infrastructure.WebhookEndpointRepository;

class DeveloperOnboardingWorkflowServiceTest {

    @Test
    void requiredSecurityStepCannotBeSkipped() {
        DeveloperOnboardingWorkflowService service = service();
        AuthenticatedUser principal = principal();

        assertThatThrownBy(() -> service.skipStep(principal, "SECURITY"))
                .isInstanceOf(com.pesaguard.backend.common.exception.BusinessException.class)
                .hasMessageContaining("required and cannot be skipped");
    }

    @Test
    void unknownStepCannotBeStarted() {
        DeveloperOnboardingWorkflowService service = service();

        assertThatThrownBy(() -> service.startStep(principal(), "CLIENT_MARKED_COMPLETE"))
                .isInstanceOf(com.pesaguard.backend.common.exception.BusinessException.class)
                .hasMessageContaining("not supported");
    }

    private DeveloperOnboardingWorkflowService service() {
        return new DeveloperOnboardingWorkflowService(
                mock(DeveloperOnboardingStatusService.class),
                mock(DeveloperOnboardingProgressRepository.class),
                mock(DeveloperOnboardingStepRepository.class),
                mock(ProjectEnvironmentRepository.class),
                mock(ApiKeyRepository.class),
                mock(ApiRequestEventRepository.class),
                mock(MfaSecretRepository.class),
                mock(IntegrationRepository.class),
                mock(WebhookEndpointRepository.class),
                mock(OrganizationMembershipRepository.class),
                mock(ProductionAccessRequestRepository.class),
                mock(AuditService.class),
                Clock.systemUTC());
    }

    private AuthenticatedUser principal() {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "developer@example.com", "Developer", Set.of());
    }
}
