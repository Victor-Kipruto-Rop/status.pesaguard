package com.pesaguard.backend.environment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.environment.domain.EnvironmentAccessPolicy;
import com.pesaguard.backend.environment.domain.EnvironmentAccessSubjectType;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.environment.infrastructure.EnvironmentAccessPolicyRepository;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.project.domain.ProjectMember;
import com.pesaguard.backend.project.domain.ProjectMemberRole;
import com.pesaguard.backend.project.domain.ProjectMemberStatus;
import com.pesaguard.backend.project.infrastructure.ProjectMemberRepository;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.security.authentication.IpRangeMatcher;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

class EnvironmentAccessPolicyServiceTest {

    private final EnvironmentAccessPolicyRepository policyRepository = mock(EnvironmentAccessPolicyRepository.class);
    private final ProjectEnvironmentRepository environmentRepository = mock(ProjectEnvironmentRepository.class);
    private final ProjectMemberRepository memberRepository = mock(ProjectMemberRepository.class);
    private final IpRangeMatcher ipRangeMatcher = mock(IpRangeMatcher.class);
    private final EnvironmentAccessPolicyService service = new EnvironmentAccessPolicyService(
            policyRepository, environmentRepository, memberRepository, mock(AuthorizationService.class),
            ipRangeMatcher, mock(AuditService.class),
            mock(com.pesaguard.backend.project.application.ProjectAuthorization.class));

    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(
            userId, UUID.randomUUID(), UUID.randomUUID(), "dev@example.test", "Developer", Set.of("ROLE_USER"));
    private final ProjectEnvironment environment = ProjectEnvironment.create(
            principal.organizationId(), projectId, "sandbox", EnvironmentType.SANDBOX, userId,
            Instant.parse("2026-01-01T00:00:00Z"));
    private final UUID environmentId = environment.getId();

    @BeforeEach
    void setUp() {
        ProjectMember member = mock(ProjectMember.class);
        when(member.getStatus()).thenReturn(ProjectMemberStatus.ACTIVE);
        when(member.getRole()).thenReturn(ProjectMemberRole.DEVELOPER);
        when(memberRepository.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(member));
    }

    @Test
    void noPolicyPreservesExistingProjectAccess() {
        when(policyRepository.findByEnvironmentIdOrderBySubjectTypeAscSubjectRoleAsc(environmentId))
                .thenReturn(List.of());

        assertThat(service.canAccess(principal, environment, EnvironmentPermission.READ)).isTrue();
    }

    @Test
    void matchingRoleMustBeGrantedTheRequestedPermission() {
        when(policyRepository.findByEnvironmentIdOrderBySubjectTypeAscSubjectRoleAsc(environmentId))
                .thenReturn(List.of(policy(Set.of(EnvironmentPermission.READ))));

        assertThat(service.canAccess(principal, environment, EnvironmentPermission.READ)).isTrue();
        assertThat(service.canAccess(principal, environment, EnvironmentPermission.WRITE)).isFalse();
    }

    @Test
    void aPolicyForAnotherRoleDoesNotGrantAccess() {
        EnvironmentAccessPolicy viewerPolicy = EnvironmentAccessPolicy.create(principal.organizationId(),
                projectId, environmentId, EnvironmentAccessSubjectType.PROJECT_MEMBER, "VIEWER",
                Set.of(EnvironmentPermission.READ), Set.of(), userId);
        when(policyRepository.findByEnvironmentIdOrderBySubjectTypeAscSubjectRoleAsc(environmentId))
                .thenReturn(List.of(viewerPolicy));

        assertThat(service.canAccess(principal, environment, EnvironmentPermission.READ)).isFalse();
    }

    @Test
    void matchingPolicyEnforcesItsIpAllowlist() {
        EnvironmentAccessPolicy policy = policy(Set.of(EnvironmentPermission.READ));
        policy.grant(Set.of(EnvironmentPermission.READ), Set.of("203.0.113.0/24"));
        when(policyRepository.findByEnvironmentIdOrderBySubjectTypeAscSubjectRoleAsc(environmentId))
                .thenReturn(List.of(policy));
        when(ipRangeMatcher.isAllowed(null, Set.of("203.0.113.0/24"))).thenReturn(false);

        assertThat(service.canAccess(principal, environment, EnvironmentPermission.READ)).isFalse();
    }

    private EnvironmentAccessPolicy policy(Set<EnvironmentPermission> permissions) {
        return EnvironmentAccessPolicy.create(principal.organizationId(), projectId, environmentId,
                EnvironmentAccessSubjectType.PROJECT_MEMBER, "DEVELOPER", permissions, Set.of(), userId);
    }
}
