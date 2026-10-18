package com.pesaguard.backend.sandbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.sandbox.domain.Sandbox;
import com.pesaguard.backend.sandbox.domain.SandboxIsolationGuard;
import com.pesaguard.backend.sandbox.domain.SandboxLimits;
import com.pesaguard.backend.sandbox.domain.SandboxStatus;
import com.pesaguard.backend.sandbox.infrastructure.SandboxHistoryRepository;
import com.pesaguard.backend.sandbox.infrastructure.SandboxIsolationGuardRepository;
import com.pesaguard.backend.sandbox.infrastructure.SandboxLimitsRepository;
import com.pesaguard.backend.sandbox.infrastructure.SandboxRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * The isolation gate, tested at request time.
 *
 * <p>{@code SandboxIsolationTest} proves the token cannot be minted for
 * production. These tests prove the gate refuses before that matters: a sandbox
 * that is not active, not pinned, or not the caller's is refused at the door.
 */
class SandboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-01T00:00:00Z");

    private final SandboxRepository sandboxRepository = mock(SandboxRepository.class);
    private final SandboxIsolationGuardRepository guardRepository = mock(SandboxIsolationGuardRepository.class);
    private final SandboxLimitsRepository limitsRepository = mock(SandboxLimitsRepository.class);
    private final SandboxHistoryRepository historyRepository = mock(SandboxHistoryRepository.class);
    private final ProjectEnvironmentRepository environmentRepository = mock(ProjectEnvironmentRepository.class);

    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private SandboxService service;

    private AuthenticatedUser principal() {
        return new AuthenticatedUser(actor, organizationId, UUID.randomUUID(),
                "dev@example.com", "Dev", Set.of("ROLE_OWNER"));
    }

    private Sandbox sandbox(SandboxStatus status) {
        Sandbox sandbox = Sandbox.create(organizationId, projectId, environmentId, "sandbox",
                null, Duration.ofDays(7), actor, NOW.minusSeconds(60));
        if (status == SandboxStatus.ACTIVE) {
            sandbox.activate(NOW.minusSeconds(30));
        } else if (status == SandboxStatus.SUSPENDED) {
            sandbox.activate(NOW.minusSeconds(30));
            sandbox.suspend(NOW.minusSeconds(20));
        }
        return sandbox;
    }

    private ProjectEnvironment environment(EnvironmentType type, EnvironmentStatus status) {
        ProjectEnvironment environment = ProjectEnvironment.create(organizationId, projectId,
                "sandbox-env", type, actor, NOW.minusSeconds(120));
        if (status == EnvironmentStatus.SUSPENDED) {
            environment.suspend(NOW);
        }
        return environment;
    }

    @BeforeEach
    void setUp() {
        service = new SandboxService(sandboxRepository, guardRepository, limitsRepository,
                historyRepository, environmentRepository, mock(AuthorizationService.class),
                mock(AuditService.class), mock(com.pesaguard.backend.environment.application.EnvironmentAccessPolicyService.class),
                mock(com.pesaguard.backend.project.application.ProjectAuthorization.class), Clock.fixed(NOW, ZoneOffset.UTC));
        // Mockito returns null from unstubbed save(); echo the argument like the
        // real repository does.
        when(sandboxRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sandboxRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                environmentId, organizationId, projectId))
                .thenReturn(Optional.of(environment(EnvironmentType.SANDBOX, EnvironmentStatus.ACTIVE)));
    }
@Test
    void aSandboxCannotBeCreatedAgainstProduction() {
        // The write-path check. Even if every other guard were bypassed, a
        // production environment cannot be adopted as a sandbox here.
        when(environmentRepository.findByIdAndOrganizationId(environmentId, organizationId))
                .thenReturn(Optional.of(environment(EnvironmentType.PRODUCTION, EnvironmentStatus.ACTIVE)));

        assertThatThrownBy(() -> service.create(principal(), environmentId, "s", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PRODUCTION");
    }

    @Test
    void aSandboxCannotBeCreatedAgainstDevelopmentOrStaging() {
        for (EnvironmentType type : List.of(EnvironmentType.DEVELOPMENT, EnvironmentType.STAGING)) {
            when(environmentRepository.findByIdAndOrganizationId(environmentId, organizationId))
                    .thenReturn(Optional.of(environment(type, EnvironmentStatus.ACTIVE)));

            assertThatThrownBy(() -> service.create(principal(), environmentId, "s", null, null))
                    .as("environment type %s must be refused", type)
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void aSandboxIsCreatedAgainstASandboxEnvironmentAndPinned() {
        ProjectEnvironment sandboxEnvironment =
                environment(EnvironmentType.SANDBOX, EnvironmentStatus.ACTIVE);
        when(environmentRepository.findByIdAndOrganizationId(environmentId, organizationId))
                .thenReturn(Optional.of(sandboxEnvironment));

        Sandbox created = service.create(principal(), environmentId, "test sandbox", "d", null);

        assertThat(created.getStatus()).isEqualTo(SandboxStatus.PROVISIONING);
        // The sandbox is pinned to the environment it was actually created
        // against, resolved from the row rather than from the request.
        assertThat(created.getEnvironmentId()).isEqualTo(sandboxEnvironment.getId());
        // Pinned at creation: this is what later makes execution provable.
        org.mockito.Mockito.verify(guardRepository).save(org.mockito.ArgumentMatchers.argThat(
                guard -> guard.getSandboxId().equals(created.getId())));
    }

    @Test
    void aSuspendedEnvironmentCannotHostANewSandbox() {
        when(environmentRepository.findByIdAndOrganizationId(environmentId, organizationId))
                .thenReturn(Optional.of(environment(EnvironmentType.SANDBOX, EnvironmentStatus.SUSPENDED)));

        assertThatThrownBy(() -> service.create(principal(), environmentId, "s", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void anActiveSandboxYieldsAnIsolationToken() {
        Sandbox sandbox = sandbox(SandboxStatus.ACTIVE);
        given(sandbox);

        var isolation = service.requireExecutableIsolation(principal(), sandbox.getId());

        assertThat(isolation.sandboxEnvironmentId()).isEqualTo(environmentId);
        assertThat(isolation.organizationId()).isEqualTo(organizationId);
    }

    @Test
    void aSuspendedSandboxIsRefusedExecution() {
        Sandbox sandbox = sandbox(SandboxStatus.SUSPENDED);
        given(sandbox);

        assertThatThrownBy(() -> service.requireExecutableIsolation(principal(), sandbox.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SUSPENDED");
    }

    @Test
    void anExpiredSandboxIsRefusedEvenBeforeTheSweepRuns() {
        Sandbox sandbox = sandbox(SandboxStatus.ACTIVE);
        when(sandboxRepository.findByIdAndOrganizationId(sandbox.getId(), organizationId))
                .thenReturn(Optional.of(sandbox));
        when(guardRepository.findBySandboxId(sandbox.getId()))
                .thenReturn(Optional.of(SandboxIsolationGuard.pin(sandbox)));

        var afterExpiry = Clock.fixed(sandbox.getExpiresAt().plusSeconds(1), ZoneOffset.UTC);
        SandboxService later = new SandboxService(sandboxRepository, guardRepository,
                limitsRepository, historyRepository, environmentRepository,
                mock(AuthorizationService.class), mock(AuditService.class),
                mock(com.pesaguard.backend.environment.application.EnvironmentAccessPolicyService.class),
                mock(com.pesaguard.backend.project.application.ProjectAuthorization.class), afterExpiry);

        assertThatThrownBy(() -> later.requireExecutableIsolation(principal(), sandbox.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("EXPIRED");
    }

    @Test
    void anotherOrganizationsSandboxIsNotFound() {
        Sandbox foreign = sandbox(SandboxStatus.ACTIVE);
        when(sandboxRepository.findByIdAndOrganizationId(foreign.getId(), UUID.randomUUID()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireExecutableIsolation(principal(), foreign.getId()))
                .isInstanceOf(com.pesaguard.backend.common.exception.ResourceNotFoundException.class);
    }

    @Test
    void resumingAnExpiredSandboxReportsConflictAndStaysExpired() {
        Sandbox sandbox = Sandbox.create(organizationId, projectId, environmentId, "s",
                null, Duration.ofHours(1), actor, NOW.minusSeconds(7200));
        sandbox.activate(NOW.minusSeconds(7100));
        sandbox.suspend(NOW.minusSeconds(7000));
        when(sandboxRepository.findByIdAndOrganizationId(sandbox.getId(), organizationId))
                .thenReturn(Optional.of(sandbox));

        assertThatThrownBy(() -> service.resume(principal(), sandbox.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
        // The flip to EXPIRED must survive the throw, or the row would keep
        // claiming SUSPENDED for a sandbox nobody can resume.
        assertThat(sandbox.getStatus()).isEqualTo(SandboxStatus.EXPIRED);
    }

    @Test
    void missingLimitsAreReportedRatherThanDefaulted() {
        // Defaulting would mean a sandbox silently running with quotas nobody set.
        Sandbox sandbox = sandbox(SandboxStatus.ACTIVE);
        when(sandboxRepository.findByIdAndOrganizationId(sandbox.getId(), organizationId))
                .thenReturn(Optional.of(sandbox));
        when(limitsRepository.findBySandboxId(sandbox.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.limits(principal(), sandbox.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no configured limits");
    }

    @Test
    void requestBodyLimitIsEnforcedBeforeExecution() {
        SandboxLimits limits = SandboxLimits.defaults(UUID.randomUUID(), organizationId);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> limits.requireRequestWithinLimit(limits.getMaxRequestBodyBytes() + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executionTimeoutIsClampedToTheSandboxCeiling() {
        SandboxLimits limits = SandboxLimits.defaults(UUID.randomUUID(), organizationId);
        int ceiling = limits.getExecutionTimeoutMs();

        // A caller asking for an hour gets the sandbox's own ceiling, never more.
        assertThat(limits.effectiveTimeoutMs(3_600_000)).isEqualTo(ceiling);
        assertThat(limits.effectiveTimeoutMs(1000)).isEqualTo(1000);
        assertThat(limits.effectiveTimeoutMs(0)).isEqualTo(1);
    }

    @Test
    void aTimeoutBeyondTheHardCeilingIsRejected() {
        SandboxLimits limits = SandboxLimits.defaults(UUID.randomUUID(), organizationId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> limits.update(10, 10, 1, 1, 1, 1,
                1024, 1024, SandboxLimits.MAX_EXECUTION_TIMEOUT_MS + 1, 10, actor))
                .isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    void anUnpinnedSandboxIsRefusedExecution() {
        // Absence of the guard is absence of proof. Without this, anyone who could
        // name any sandbox id could execute without ever proving it was pinned.
        Sandbox sandbox = sandbox(SandboxStatus.ACTIVE);
        when(sandboxRepository.findByIdAndOrganizationId(sandbox.getId(), organizationId))
                .thenReturn(Optional.of(sandbox));
        when(guardRepository.findBySandboxId(sandbox.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireExecutableIsolation(principal(), sandbox.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("isolation guard");
    }

    private void given(Sandbox sandbox) {
        when(sandboxRepository.findByIdAndOrganizationId(sandbox.getId(), organizationId))
                .thenReturn(Optional.of(sandbox));
        when(guardRepository.findBySandboxId(sandbox.getId()))
                .thenReturn(Optional.of(SandboxIsolationGuard.pin(sandbox)));
    }
}
