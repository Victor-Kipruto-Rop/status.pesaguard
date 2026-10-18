package com.pesaguard.backend.project.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.project.api.ProjectSettingsView;
import com.pesaguard.backend.project.api.UpdateProjectSettingsRequest;
import com.pesaguard.backend.project.domain.Project;
import com.pesaguard.backend.project.domain.ProjectSettings;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.project.infrastructure.ProjectSettingsRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Project settings are non-secret configuration only. Credential-like values are
 * rejected by the metadata validator and belong in environment credentials.
 */
@Service
public class ProjectSettingsService {

    private final ProjectRepository projectRepository;
    private final ProjectSettingsRepository settingsRepository;
    private final ProjectAuthorization authorization;
    private final ProjectMetadataValidator metadataValidator;
    private final AuditService auditService;
    private final Clock clock;

    public ProjectSettingsService(
            ProjectRepository projectRepository,
            ProjectSettingsRepository settingsRepository,
            ProjectAuthorization authorization,
            ProjectMetadataValidator metadataValidator,
            AuditService auditService,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.settingsRepository = settingsRepository;
        this.authorization = authorization;
        this.metadataValidator = metadataValidator;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public ProjectSettingsView get(AuthenticatedUser principal, UUID projectId) {
        requireProject(principal, projectId);
        authorization.requireProjectRead(principal, projectId);
        return toView(getOrCreate(principal.organizationId(), projectId));
    }

    @Transactional
    public ProjectSettingsView update(AuthenticatedUser principal, UUID projectId,
            UpdateProjectSettingsRequest request) {
        requireProject(principal, projectId);
        authorization.requireProjectManage(principal, projectId);
        metadataValidator.validate(request.settings());
        ProjectSettings settings = getOrCreate(principal.organizationId(), projectId);
        settings.update(request.settings(), principal.userId(), clock.instant());
        settingsRepository.saveAndFlush(settings);
        auditService.append(principal.organizationId(), principal.userId(), "project.settings_updated",
                "project_settings", projectId.toString(), RequestContext.currentRequestId(), Map.of());
        return toView(settings);
    }

    private ProjectSettings getOrCreate(UUID organizationId, UUID projectId) {
        return settingsRepository.findByProjectIdAndOrganizationId(projectId, organizationId)
                .orElseGet(() -> settingsRepository.saveAndFlush(
                        ProjectSettings.defaults(projectId, organizationId, clock.instant())));
    }

    private void requireProject(AuthenticatedUser principal, UUID projectId) {
        projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Project"));
    }

    private ProjectSettingsView toView(ProjectSettings settings) {
        return new ProjectSettingsView(settings.getProjectId(), settings.getSettings(),
                settings.getUpdatedBy(), settings.getUpdatedAt());
    }
}