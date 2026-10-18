package com.pesaguard.backend.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.onboarding.infrastructure.DeveloperOnboardingProgressRepository;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.project.domain.ProjectStatus;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

class DeveloperOnboardingStatusServiceTest {

    @Test
    void reportsCompleteOnlyForVerifiedAccountWithProjectAndEnvironment() {
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        OrganizationMembershipRepository membershipRepository = mock(OrganizationMembershipRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectEnvironmentRepository environmentRepository = mock(ProjectEnvironmentRepository.class);
        DeveloperOnboardingProgressRepository progressRepository = mock(DeveloperOnboardingProgressRepository.class);
        UserAccount user = UserAccount.create("developer@example.com", "Developer", "password-hash");
        user.beginEmailVerification("verification-hash", Instant.parse("2026-01-01T00:00:00Z"));
        user.verifyEmail(Instant.parse("2026-01-01T00:01:00Z"));
        UUID userId = user.getId();
        Organization organization = Organization.create("Workspace", "workspace", userId, Instant.now());
        organization.update("Workspace", organization.getOrganizationType(),
                java.util.Map.of("description", "An organization for API integrations."));
        OrganizationMembership membership = OrganizationMembership.owner(organization, user);
        UUID organizationId = organization.getId();
        AuthenticatedUser principal = new AuthenticatedUser(
                userId, organizationId, UUID.randomUUID(), user.getEmail(), user.getDisplayName(), Set.of());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(membershipRepository.findByOrganizationIdAndUserId(organizationId, userId))
                .thenReturn(Optional.of(membership));
        when(projectRepository.existsByOrganizationIdAndStatus(organizationId, ProjectStatus.ACTIVE)).thenReturn(true);
        when(projectRepository.findFirstByOrganizationIdAndStatusOrderByCreatedAtAsc(
                organizationId, ProjectStatus.ACTIVE)).thenReturn(Optional.empty());
        when(environmentRepository.existsActiveEnvironmentForOrganization(organizationId, "ACTIVE")).thenReturn(true);

        var status = new DeveloperOnboardingStatusService(
                userRepository, membershipRepository, projectRepository, environmentRepository,
                progressRepository, Clock.fixed(Instant.parse("2026-01-01T00:02:00Z"), ZoneOffset.UTC)).statusFor(principal);

        assertThat(status).satisfies(result -> {
            assertThat(result.emailVerified()).isTrue();
            assertThat(result.organizationReady()).isTrue();
            assertThat(result.projectReady()).isTrue();
            assertThat(result.environmentReady()).isTrue();
            assertThat(result.complete()).isTrue();
        });
    }

    @Test
    void rejectsUnverifiedAccountBeforeSetupCanContinue() {
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        OrganizationMembershipRepository membershipRepository = mock(OrganizationMembershipRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectEnvironmentRepository environmentRepository = mock(ProjectEnvironmentRepository.class);
        DeveloperOnboardingProgressRepository progressRepository = mock(DeveloperOnboardingProgressRepository.class);
        UUID userId = UUID.randomUUID();
        UserAccount user = UserAccount.create("developer@example.com", "Developer", "password-hash");
        AuthenticatedUser principal = new AuthenticatedUser(
                userId, UUID.randomUUID(), UUID.randomUUID(), user.getEmail(), user.getDisplayName(), Set.of());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> new DeveloperOnboardingStatusService(
                userRepository, membershipRepository, projectRepository, environmentRepository,
                progressRepository, Clock.systemUTC()).statusFor(principal))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Verify your email");
    }
}
