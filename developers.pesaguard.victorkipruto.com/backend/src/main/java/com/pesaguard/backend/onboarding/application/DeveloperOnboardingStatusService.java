package com.pesaguard.backend.onboarding.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.onboarding.api.DeveloperOnboardingStatus;
import com.pesaguard.backend.onboarding.api.UpdateOnboardingProgressRequest;
import com.pesaguard.backend.onboarding.api.UpdateDeveloperProfileRequest;
import com.pesaguard.backend.onboarding.domain.DeveloperOnboardingProgress;
import com.pesaguard.backend.onboarding.infrastructure.DeveloperOnboardingProgressRepository;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.project.domain.ProjectStatus;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class DeveloperOnboardingStatusService {

    private final UserAccountRepository userRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final DeveloperOnboardingProgressRepository progressRepository;
    private final Clock clock;

    public DeveloperOnboardingStatusService(
            UserAccountRepository userRepository,
            OrganizationMembershipRepository membershipRepository,
            ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository,
            DeveloperOnboardingProgressRepository progressRepository,
            Clock clock) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.progressRepository = progressRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DeveloperOnboardingStatus statusFor(AuthenticatedUser principal) {
        var user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE",
                        "The account is not available."));
        if (!user.isActive()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE",
                    "This account is not active.");
        }
        if (!user.isEmailVerified()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
                    "Verify your email address before continuing.");
        }
        OrganizationMembership membership = membershipRepository
                .findByOrganizationIdAndUserId(principal.organizationId(), principal.userId())
                .filter(OrganizationMembership::isActive)
                .filter(candidate -> candidate.getOrganization().isActive())
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.FORBIDDEN, "ORGANIZATION_UNAVAILABLE",
                        "Your organization membership is not active."));
        boolean profileReady = user.getDisplayName() != null && !user.getDisplayName().isBlank();
        boolean projectReady = projectRepository.existsByOrganizationIdAndStatus(
                membership.getOrganization().getId(), ProjectStatus.ACTIVE);
        boolean environmentReady = environmentRepository.existsActiveEnvironmentForOrganization(
                membership.getOrganization().getId(), ProjectStatus.ACTIVE.name());
        var firstProject = projectRepository.findFirstByOrganizationIdAndStatusOrderByCreatedAtAsc(
                membership.getOrganization().getId(), ProjectStatus.ACTIVE).orElse(null);
        var firstEnvironment = firstProject == null ? null
                : environmentRepository.findFirstByOrganizationIdAndProjectIdAndStatusOrderByCreatedAtAsc(
                        membership.getOrganization().getId(), firstProject.getId(), EnvironmentStatus.ACTIVE)
                        .orElse(null);
        Object description = membership.getOrganization().getMetadata().get("description");
        boolean organizationReady = description instanceof String value && value.trim().length() >= 10;
        boolean complete = profileReady && organizationReady && projectReady && environmentReady;
        String nextStep = !profileReady ? "profile" : !organizationReady ? "organization"
                : !projectReady ? "project" : !environmentReady ? "environment" : "complete";
        DeveloperOnboardingProgress progress = progressRepository.findById(principal.userId()).orElse(null);
        List<String> completedSteps = progress == null ? List.of() : progress.getCompletedSteps();
        List<String> recommendations = recommendations(projectReady, environmentReady, completedSteps);
        String currentStep = progress == null ? nextStep : progress.getCurrentStep();
        String organizationName = membership.getOrganization().getName();
        String organizationDescription = description instanceof String value ? value : "";
        return new DeveloperOnboardingStatus(true, profileReady, organizationReady,
                projectReady, environmentReady, nextStep, complete,
                progress != null, progress != null && progress.isCompleted(),
                progress != null && progress.isSkipped(), currentStep, completedSteps, recommendations,
                organizationName, organizationDescription,
                firstProject == null ? null : firstProject.getId(),
                firstProject == null ? null : firstProject.getName(),
                firstEnvironment == null ? null : firstEnvironment.getId(),
                firstEnvironment == null ? null : firstEnvironment.getName());
    }

    public void requireVerified(AuthenticatedUser principal) {
        DeveloperOnboardingStatus status = statusFor(principal);
        if (!status.profileReady() || !status.organizationReady()) {
            throw new BusinessException(HttpStatus.CONFLICT, "ONBOARDING_STEP_REQUIRED",
                    "Complete your developer profile and organization details before creating a project.");
        }
    }

    @Transactional
    public DeveloperOnboardingStatus updateProfile(
            AuthenticatedUser principal, UpdateDeveloperProfileRequest request) {
        var user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE",
                        "The account is not available."));
        if (!user.isActive() || !user.isEmailVerified()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE",
                    "An active, verified account is required to update the profile.");
        }
        user.updateDisplayName(request.displayName(), java.time.Instant.now());
        userRepository.save(user);
        return statusFor(principal);
    }

    @Transactional
    public DeveloperOnboardingStatus recordProgress(
            AuthenticatedUser principal, UpdateOnboardingProgressRequest request) {
        DeveloperOnboardingProgress progress = progressFor(principal);
        progress.update(request.currentStep(), request.completedStep(), clock.instant());
        progressRepository.save(progress);
        return statusFor(principal);
    }

    @Transactional
    public DeveloperOnboardingStatus skip(AuthenticatedUser principal) {
        DeveloperOnboardingProgress progress = progressFor(principal);
        progress.skip(clock.instant());
        progressRepository.save(progress);
        return statusFor(principal);
    }

    @Transactional
    public DeveloperOnboardingStatus resume(AuthenticatedUser principal) {
        DeveloperOnboardingProgress progress = progressFor(principal);
        DeveloperOnboardingStatus status = statusFor(principal);
        String next = progress.getCurrentStep();
        if ("complete".equals(next) && !progress.isCompleted()) {
            next = status.nextStep();
        }
        progress.resume(next, clock.instant());
        progressRepository.save(progress);
        return statusFor(principal);
    }

    @Transactional
    public DeveloperOnboardingStatus complete(AuthenticatedUser principal) {
        DeveloperOnboardingStatus status = statusFor(principal);
        if (!status.complete()) {
            throw new BusinessException(HttpStatus.CONFLICT, "ONBOARDING_REQUIREMENTS_INCOMPLETE",
                    "Create an organization, project, and environment before completing setup.");
        }
        DeveloperOnboardingProgress progress = progressFor(principal);
        progress.complete(List.of("welcome", "organization", "project", "environment", "api-key",
                "first-request", "api-explorer", "webhook", "documentation", "production-readiness"),
                clock.instant());
        progressRepository.save(progress);
        return statusFor(principal);
    }

    private DeveloperOnboardingProgress progressFor(AuthenticatedUser principal) {
        statusFor(principal);
        return progressRepository.findById(principal.userId())
                .orElseGet(() -> progressRepository.save(
                        DeveloperOnboardingProgress.start(principal.userId(), clock.instant())));
    }

    private List<String> recommendations(
            boolean projectReady, boolean environmentReady, List<String> completedSteps) {
        List<String> result = new ArrayList<>();
        if (!projectReady) result.add("Create a named project for your integration.");
        if (!environmentReady) result.add("Start in an isolated sandbox environment.");
        if (!completedSteps.contains("api-key")) result.add("Create a short-lived, narrowly scoped API key.");
        if (!completedSteps.contains("webhook")) result.add("Connect a webhook to receive event updates.");
        if (!completedSteps.contains("documentation")) result.add("Bookmark the API reference and developer guides.");
        if (!completedSteps.contains("production-readiness")) result.add("Review production readiness before going live.");
        return result;
    }
}
