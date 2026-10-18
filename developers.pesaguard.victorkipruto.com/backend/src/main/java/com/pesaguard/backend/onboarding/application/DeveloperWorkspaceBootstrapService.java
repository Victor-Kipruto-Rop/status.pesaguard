package com.pesaguard.backend.onboarding.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.credentials.api.ApiKeyService;
import com.pesaguard.backend.credentials.api.CreateApiKeyRequest;
import com.pesaguard.backend.credentials.api.CreatedApiKeyView;
import com.pesaguard.backend.environment.api.CreateEnvironmentRequest;
import com.pesaguard.backend.environment.api.EnvironmentView;
import com.pesaguard.backend.environment.application.EnvironmentService;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.onboarding.api.BootstrapDeveloperWorkspaceRequest;
import com.pesaguard.backend.onboarding.api.DeveloperWorkspaceBootstrapView;
import com.pesaguard.backend.onboarding.api.OnboardingApiKeyRequest;
import com.pesaguard.backend.onboarding.api.OnboardingEnvironmentRequest;
import com.pesaguard.backend.project.api.CreateProjectRequest;
import com.pesaguard.backend.project.api.ProjectView;
import com.pesaguard.backend.project.api.UpdateProjectRequest;
import com.pesaguard.backend.project.application.ProjectService;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.sandbox.application.SandboxService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@Service
public class DeveloperWorkspaceBootstrapService {

    private record ProjectTemplate(String description) {
    }

    private final ProjectService projectService;
    private final EnvironmentService environmentService;
    private final SandboxService sandboxService;
    private final ApiKeyService apiKeyService;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final DeveloperOnboardingStatusService onboardingStatusService;
    private final java.time.Clock clock;

    public DeveloperWorkspaceBootstrapService(
            ProjectService projectService,
            EnvironmentService environmentService,
            SandboxService sandboxService,
            ApiKeyService apiKeyService,
            ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository,
            DeveloperOnboardingStatusService onboardingStatusService,
            java.time.Clock clock) {
        this.projectService = projectService;
        this.environmentService = environmentService;
        this.sandboxService = sandboxService;
        this.apiKeyService = apiKeyService;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.onboardingStatusService = onboardingStatusService;
        this.clock = clock;
    }

    /** Creates only the project the developer explicitly requested. */
    @Transactional
    public DeveloperWorkspaceBootstrapView bootstrap(
            AuthenticatedUser principal, BootstrapDeveloperWorkspaceRequest request) {
        onboardingStatusService.requireVerified(principal);
        ProjectTemplate selectedTemplate = template(request.template());
        String projectName = request.projectName().trim();
        String slug = uniqueSlug(projectName, principal.organizationId());
        ProjectView project = projectService.create(principal, new CreateProjectRequest(projectName, slug));
        project = projectService.update(principal, project.id(), new UpdateProjectRequest(
                projectName, selectedTemplate.description(), Map.of("template", request.template())));
        return new DeveloperWorkspaceBootstrapView(
                project, request.template(), "/api/v1/sandbox/transactions");
    }

    @Transactional
    public EnvironmentView createEnvironment(
            AuthenticatedUser principal, UUID projectId, OnboardingEnvironmentRequest request) {
        onboardingStatusService.requireVerified(principal);
        if (request.type() != EnvironmentType.SANDBOX) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ONBOARDING_SANDBOX_REQUIRED",
                    "The first onboarding environment must be a sandbox.");
        }
        EnvironmentView environment = environmentService.create(principal, projectId,
                new CreateEnvironmentRequest(request.name(), request.type()));
        var sandbox = sandboxService.create(principal, environment.id(), "Getting started sandbox",
                "Created for the developer's first sandbox API request.", java.time.Duration.ofDays(7));
        sandboxService.activate(principal, sandbox.getId());
        return environment;
    }

    @Transactional
    public CreatedApiKeyView createFirstApiKey(
            AuthenticatedUser principal, OnboardingApiKeyRequest request) {
        onboardingStatusService.requireVerified(principal);
        var environment = environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        request.environmentId(), principal.organizationId(), request.projectId())
                .orElseThrow(() -> new com.pesaguard.backend.common.exception.ResourceNotFoundException("Environment"));
        if (environment.getType() != EnvironmentType.SANDBOX) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ONBOARDING_SANDBOX_REQUIRED",
                    "The first onboarding API key must belong to a sandbox environment.");
        }
        return apiKeyService.issue(principal, request.projectId(), request.environmentId(),
                new CreateApiKeyRequest("First sandbox API key", Set.of("transactions:read"),
                        "PT720H", Set.of()), clock.instant(), request.idempotencyKey().toString());
    }

    private String uniqueSlug(String name, UUID organizationId) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (normalized.isEmpty() || !Character.isLetter(normalized.charAt(0))) {
            normalized = "project-" + normalized;
        }
        String base = normalized.substring(0, Math.min(70, normalized.length())).replaceAll("-+$", "");
        String candidate = base.length() < 2 ? "developer-project" : base;
        while (projectRepository.existsByOrganizationIdAndSlug(organizationId, candidate)) {
            candidate = base + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return candidate;
    }

    private ProjectTemplate template(String template) {
        return switch (template) {
            case "payments" -> new ProjectTemplate("PesaGuard payments integration project.");
            case "events" -> new ProjectTemplate("PesaGuard event-driven integration project.");
            case "risk" -> new ProjectTemplate("PesaGuard risk-monitoring starter using read-only usage analytics.");
            default -> throw new IllegalArgumentException("Unsupported project template.");
        };
    }
}
