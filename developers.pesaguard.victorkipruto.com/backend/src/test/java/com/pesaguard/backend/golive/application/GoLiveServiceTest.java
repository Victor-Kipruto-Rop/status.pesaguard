package com.pesaguard.backend.golive.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.environment.application.EnvironmentAccessPolicyService;
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.golive.domain.GoLiveLaunch;
import com.pesaguard.backend.golive.domain.GoLiveVerification;
import com.pesaguard.backend.golive.domain.GoLiveVerificationJob;
import com.pesaguard.backend.golive.infrastructure.GoLiveLaunchRepository;
import com.pesaguard.backend.golive.infrastructure.GoLiveVerificationJobRepository;
import com.pesaguard.backend.golive.infrastructure.GoLiveVerificationRepository;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.domain.UserStatus;
import com.pesaguard.backend.member.infrastructure.MfaSecretRepository;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationSecuritySettingsRepository;
import com.pesaguard.backend.project.application.ProjectAuthorization;
import com.pesaguard.backend.project.domain.Project;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.webhooks.infrastructure.WebhookEndpointRepository;

class GoLiveServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final ProjectEnvironmentRepository environmentRepository = mock(ProjectEnvironmentRepository.class);
    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
    private final WebhookEndpointRepository webhookEndpointRepository = mock(WebhookEndpointRepository.class);
    private final GoLiveVerificationRepository verificationRepository = mock(GoLiveVerificationRepository.class);
    private final GoLiveVerificationJobRepository verificationJobRepository =
            mock(GoLiveVerificationJobRepository.class);
    private final GoLiveLaunchRepository launchRepository = mock(GoLiveLaunchRepository.class);
    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final OrganizationMembershipRepository membershipRepository =
            mock(OrganizationMembershipRepository.class);
    private final OrganizationSecuritySettingsRepository securitySettingsRepository =
            mock(OrganizationSecuritySettingsRepository.class);
    private final MfaSecretRepository mfaSecretRepository = mock(MfaSecretRepository.class);
    private final AuthorizationService authorizationService = mock(AuthorizationService.class);
    private final ProjectAuthorization projectAuthorization = mock(ProjectAuthorization.class);
    private final EnvironmentAccessPolicyService environmentAccessPolicyService =
            mock(EnvironmentAccessPolicyService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(userId, organizationId,
            UUID.randomUUID(), "go-live@example.test", "Go-Live user", Set.of("ROLE_USER"));
    private final Project project = Project.create(organizationId, "Payment platform", "payments",
            userId, NOW);
    private final UUID projectId = project.getId();
    private final ProjectEnvironment environment = ProjectEnvironment.create(organizationId,
            projectId, "production", EnvironmentType.PRODUCTION, userId, NOW);
    private GoLiveVerification persistedVerification;
    private GoLiveService service;

    @BeforeEach
    void setUp() {
        persistedVerification = null;
        when(projectRepository.findByIdAndOrganizationId(projectId, organizationId))
                .thenReturn(Optional.of(project));
        UserAccount account = mock(UserAccount.class);
        when(account.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(account.isEmailVerified()).thenReturn(true);
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(account));
        Organization organization = mock(Organization.class);
        when(organization.getStatus()).thenReturn(OrganizationStatus.ACTIVE);
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        var membership = mock(com.pesaguard.backend.organization.domain.OrganizationMembership.class);
        when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        when(membership.getRole()).thenReturn(OrganizationRole.ADMIN);
        when(membershipRepository.findByOrganizationIdAndUserId(organizationId, userId))
                .thenReturn(Optional.of(membership));
        when(authorizationService.hasPermission(
                org.mockito.ArgumentMatchers.any(), eq(com.pesaguard.backend.rbac.domain.Permission.PRODUCTION_VERIFY)))
                .thenReturn(true);
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                environment.getId(), organizationId, projectId)).thenReturn(Optional.of(environment));
        when(environmentRepository.findByIdAndOrganizationIdAndProjectIdForUpdate(
                environment.getId(), organizationId, projectId)).thenReturn(Optional.of(environment));
        when(apiKeyRepository.countUsableProductionKeys(eq(environment.getId()), eq(NOW))).thenReturn(1L);
        when(webhookEndpointRepository
                .findByOrganizationIdAndProjectIdAndEnvironmentIdAndStatusNotOrderByCreatedAtDesc(
                        eq(organizationId), eq(projectId), eq(environment.getId()), eq("DELETED"),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(launchRepository.existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
                organizationId, projectId, environment.getId(), "LIVE")).thenReturn(false);
        when(verificationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            persistedVerification = invocation.getArgument(0);
            return persistedVerification;
        });
        when(verificationRepository.findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                any(UUID.class), eq(organizationId), eq(projectId), eq(environment.getId())))
                .thenAnswer(invocation -> Optional.ofNullable(persistedVerification)
                        .filter(verification -> verification.getId().equals(invocation.getArgument(0))));
        when(verificationJobRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationRepository.findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByVerifiedAtDesc(
                eq(organizationId), eq(projectId), eq(environment.getId()), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(GoLiveVerification.create(organizationId, projectId,
                        environment.getId(), userId, null, "READY", 100, 4, 0, 0, List.of(), NOW)));
        when(launchRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new GoLiveService(projectRepository, environmentRepository, apiKeyRepository,
                webhookEndpointRepository, verificationRepository, verificationJobRepository, launchRepository,
                userAccountRepository, organizationRepository, membershipRepository,
                securitySettingsRepository, mfaSecretRepository,
                authorizationService, projectAuthorization, environmentAccessPolicyService,
                auditService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void readinessIsBackendComputedAndDoesNotRevealCredentialSecrets() {
        var readiness = service.readiness(principal, projectId, environment.getId());

        assertThat(readiness.canLaunch()).isTrue();
        assertThat(readiness.state()).isEqualTo("READY");
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.checkId()).isEqualTo("production-credentials");
            assertThat(check.result()).contains("active key");
            assertThat(check.result()).doesNotContain("pgk_");
        });
        assertThat(readiness.checks()).anySatisfy(check ->
                assertThat(check.status()).isEqualTo("WARNING"));
    }

    @Test
    void readinessDoesNotAllowLaunchWithoutARecentPersistedVerification() {
        when(verificationRepository.findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByVerifiedAtDesc(
                eq(organizationId), eq(projectId), eq(environment.getId()),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        var readiness = service.readiness(principal, projectId, environment.getId());

        assertThat(readiness.state()).isEqualTo("NOT_STARTED");
        assertThat(readiness.canLaunch()).isFalse();
        assertThat(readiness.verificationId()).isNull();
        assertThat(readiness.verifiedAt()).isNull();
    }

    @Test
    void readinessKeepsPersistedLiveStateAfterReload() {
        when(launchRepository.existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
                organizationId, projectId, environment.getId(), "LIVE")).thenReturn(true);

        var readiness = service.readiness(principal, projectId, environment.getId());

        assertThat(readiness.state()).isEqualTo("LIVE");
        assertThat(readiness.canLaunch()).isFalse();
    }

    @Test
    void readinessKeepsPersistedLiveStateWhenEnvironmentIsSuspended() {
        when(launchRepository.existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
                organizationId, projectId, environment.getId(), "LIVE")).thenReturn(true);
        environment.suspend(NOW);

        var readiness = service.readiness(principal, projectId, environment.getId());

        assertThat(readiness.state()).isEqualTo("LIVE");
        assertThat(readiness.canLaunch()).isFalse();
    }

    @Test
    void readinessReportsRevokedLaunchAndAllowsAQualifiedNewLaunch() {
        when(launchRepository.existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
                organizationId, projectId, environment.getId(), "REVOKED")).thenReturn(true);

        var readiness = service.readiness(principal, projectId, environment.getId());

        assertThat(readiness.state()).isEqualTo("REVOKED");
        assertThat(readiness.canLaunch()).isTrue();
    }

    @Test
    void readinessUsesCurrentOrganizationStatusFromTheRepository() {
        Organization suspendedOrganization = mock(Organization.class);
        when(suspendedOrganization.getStatus()).thenReturn(OrganizationStatus.SUSPENDED);
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(suspendedOrganization));

        var readiness = service.readiness(principal, projectId, environment.getId());

        assertThat(readiness.canLaunch()).isFalse();
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.checkId()).isEqualTo("organization-active");
            assertThat(check.status()).isEqualTo("FAILED");
        });
    }

    @Test
    void sandboxEnvironmentIsRejectedForGoLive() {
        ProjectEnvironment sandbox = ProjectEnvironment.create(organizationId, projectId,
                "sandbox", EnvironmentType.SANDBOX, userId, NOW);
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                sandbox.getId(), organizationId, projectId)).thenReturn(Optional.of(sandbox));

        assertThatThrownBy(() -> service.readiness(principal, projectId, sandbox.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Production environment");
    }

    @Test
    void organizationMfaPolicyBlocksLaunchUntilTheInitiatorHasAConfirmedFactor() {
        OrganizationSecuritySettings settings = mock(OrganizationSecuritySettings.class);
        when(settings.isMfaRequired()).thenReturn(true);
        when(securitySettingsRepository.findById(organizationId)).thenReturn(Optional.of(settings));

        var readiness = service.readiness(principal, projectId, environment.getId());

        assertThat(readiness.canLaunch()).isFalse();
        assertThat(readiness.checks()).anySatisfy(check -> {
            assertThat(check.checkId()).isEqualTo("account-mfa-policy");
            assertThat(check.status()).isEqualTo("FAILED");
            assertThat(check.severity()).isEqualTo("CRITICAL");
        });
    }

    @Test
    void verificationCompletesBeforeTheRequestReturns() {
        var first = service.startVerification(principal, projectId, environment.getId(), "verify-key-123");

        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.result()).isNotNull();
        assertThat(first.result().state()).isEqualTo("READY");
        assertThat(first.completedAt()).isNotNull();
        verify(verificationJobRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                job -> job.getStatus().equals("COMPLETED")
                        && job.getOrganizationId().equals(organizationId)));
        verify(verificationRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                verification -> verification.getState().equals("READY")
                        && verification.getInitiatedBy().equals(userId)));
        verify(auditService).append(
                eq(organizationId),
                eq(userId),
                eq("golive.verification.completed"),
                eq("golive_verification"),
                any(String.class),
                org.mockito.ArgumentMatchers.argThat(requestId -> requestId != null),
                any(java.util.Map.class));
    }

    @Test
    void verificationWorkerPersistsTheSnapshotAndCompletesTheJob() {
        GoLiveVerificationJob job = GoLiveVerificationJob.queue(organizationId, projectId,
                environment.getId(), userId, "verify-key-123", NOW);
        when(verificationJobRepository.findFirstByStatusOrderByQueuedAtAsc("QUEUED"))
                .thenReturn(Optional.of(job));

        service.processNextVerificationJob();

        assertThat(job.getStatus()).isEqualTo("COMPLETED");
        assertThat(job.getVerificationId()).isNotNull();
        verify(verificationRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                verification -> verification.getState().equals("READY")
                        && verification.getInitiatedBy().equals(userId)));
    }

    @Test
    void verificationWorkerFailsWhenInitiatorMembershipIsNoLongerActive() {
        GoLiveVerificationJob job = GoLiveVerificationJob.queue(organizationId, projectId,
                environment.getId(), userId, "verify-key-123", NOW);
        when(verificationJobRepository.findFirstByStatusOrderByQueuedAtAsc("QUEUED"))
                .thenReturn(Optional.of(job));
        when(membershipRepository.findByOrganizationIdAndUserId(organizationId, userId))
                .thenReturn(Optional.empty());

        service.processNextVerificationJob();

        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getFailureReason()).contains("initiator");
        verify(verificationRepository, never()).saveAndFlush(any());
        verify(auditService).append(
                eq(organizationId),
                eq(userId),
                eq("golive.verification.failed"),
                eq("golive_verification_job"),
                eq(job.getId().toString()),
                org.mockito.ArgumentMatchers.argThat(requestId -> requestId != null),
                any(java.util.Map.class));
    }

    @Test
    void verificationWorkerFailsWhenInitiatorNoLongerHasVerificationPermission() {
        GoLiveVerificationJob job = GoLiveVerificationJob.queue(organizationId, projectId,
                environment.getId(), userId, "verify-key-123", NOW);
        when(verificationJobRepository.findFirstByStatusOrderByQueuedAtAsc("QUEUED"))
                .thenReturn(Optional.of(job));
        when(authorizationService.hasPermission(
                org.mockito.ArgumentMatchers.any(), eq(com.pesaguard.backend.rbac.domain.Permission.PRODUCTION_VERIFY)))
                .thenReturn(false);

        service.processNextVerificationJob();

        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getFailureReason()).contains("authorized");
        verify(verificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void launchPersistsAReferenceWhenReadinessIsBlocked() {
        when(apiKeyRepository.countUsableProductionKeys(eq(environment.getId()), eq(NOW))).thenReturn(0L);

        var launch = service.launch(principal, projectId, environment.getId(), "request-key-123");

        assertThat(launch.status()).isEqualTo("LAUNCH_FAILED");
        assertThat(launch.failureReason()).contains("readiness checks");
        verify(launchRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                saved -> saved.getStatus().equals("LAUNCH_FAILED")));
    }

    @Test
    void launchRejectsAnExpiredVerification() {
        GoLiveVerification expired = GoLiveVerification.create(organizationId, projectId,
                environment.getId(), userId, null, "READY", 100, 4, 0, 0,
                List.of(), NOW.minus(Duration.ofMinutes(16)));
        when(verificationRepository.findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByVerifiedAtDesc(
                eq(organizationId), eq(projectId), eq(environment.getId()),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(expired));

        assertThatThrownBy(() -> service.launch(principal, projectId, environment.getId(), "request-key-123"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("15 minutes old");
    }

    @Test
    void launchRetriesWithTheSameIdempotencyKeyReturnTheOriginalLaunch() {
        GoLiveLaunch previous = GoLiveLaunch.completed(organizationId, projectId, environment.getId(),
                userId, UUID.randomUUID(), "request-key-123", NOW, NOW, null);
        when(launchRepository.findByOrganizationIdAndProjectIdAndEnvironmentIdAndIdempotencyKey(
                organizationId, projectId, environment.getId(), "request-key-123"))
                .thenReturn(Optional.of(previous));

        var retry = service.launch(principal, projectId, environment.getId(), "request-key-123");

        assertThat(retry.id()).isEqualTo(previous.getId());
        verify(launchRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void launchedProductionCanBeSuspendedAndResumedAfterReadinessPasses() {
        when(launchRepository.existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
                organizationId, projectId, environment.getId(), "LIVE")).thenReturn(true);
        when(environmentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var suspended = service.suspend(principal, projectId, environment.getId());

        assertThat(suspended.state()).isEqualTo("SUSPENDED");
        assertThat(environment.getStatus()).isEqualTo(EnvironmentStatus.SUSPENDED);

        var resumed = service.resume(principal, projectId, environment.getId());

        assertThat(resumed.state()).isEqualTo("LIVE");
        assertThat(environment.getStatus()).isEqualTo(EnvironmentStatus.ACTIVE);
    }

    @Test
    void launchRequiresAnIdempotencyKey() {
        assertThatThrownBy(() -> service.launch(principal, projectId, environment.getId(), " "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("idempotency key");
    }
}
