package com.pesaguard.backend.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.pesaguard.backend.credentials.api.ApiKeyService;
import com.pesaguard.backend.environment.application.EnvironmentService;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.onboarding.api.BootstrapDeveloperWorkspaceRequest;
import com.pesaguard.backend.project.api.CreateProjectRequest;
import com.pesaguard.backend.project.api.ProjectView;
import com.pesaguard.backend.project.api.UpdateProjectRequest;
import com.pesaguard.backend.project.application.ProjectService;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.sandbox.application.SandboxService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

class DeveloperWorkspaceBootstrapServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void createsOnlyTheProjectExplicitlyRequestedByTheDeveloper() {
        ProjectService projectService = mock(ProjectService.class);
        EnvironmentService environmentService = mock(EnvironmentService.class);
        SandboxService sandboxService = mock(SandboxService.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectEnvironmentRepository environmentRepository = mock(ProjectEnvironmentRepository.class);
        DeveloperOnboardingStatusService onboardingStatusService = mock(DeveloperOnboardingStatusService.class);
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        AuthenticatedUser principal = new AuthenticatedUser(userId, organizationId, UUID.randomUUID(),
                "developer@example.com", "Developer", Set.of("ROLE_OWNER"));
        ProjectView project = new ProjectView(projectId, "Risk dashboard", "risk-dashboard",
                null, null, userId, Map.of(), NOW, NOW, NOW);
        when(projectService.create(eq(principal), any())).thenReturn(project);
        when(projectService.update(eq(principal), eq(projectId), any())).thenReturn(project);

        DeveloperWorkspaceBootstrapService service = new DeveloperWorkspaceBootstrapService(
                projectService, environmentService, sandboxService, apiKeyService,
                projectRepository, environmentRepository, onboardingStatusService, Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.bootstrap(principal,
                new BootstrapDeveloperWorkspaceRequest("Risk dashboard", "risk"));

        assertThat(result.project()).isEqualTo(project);
        assertThat(result.template()).isEqualTo("risk");
        assertThat(result.firstEndpoint()).isEqualTo("/api/v1/sandbox/transactions");

        ArgumentCaptor<CreateProjectRequest> create = ArgumentCaptor.forClass(CreateProjectRequest.class);
        verify(projectService).create(eq(principal), create.capture());
        assertThat(create.getValue().name()).isEqualTo("Risk dashboard");

        ArgumentCaptor<UpdateProjectRequest> update = ArgumentCaptor.forClass(UpdateProjectRequest.class);
        verify(projectService).update(eq(principal), eq(projectId), update.capture());
        assertThat(update.getValue().description()).contains("read-only usage analytics");
        assertThat(update.getValue().metadata()).containsEntry("template", "risk");

        verify(environmentService, never()).create(eq(principal), eq(projectId), any());
        verify(sandboxService, never()).create(eq(principal), any(), any(), any(), any());
        verify(apiKeyService, never()).issue(eq(principal), eq(projectId), any(), any(), any());
    }
}
