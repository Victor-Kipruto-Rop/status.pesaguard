package com.pesaguard.backend.project.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.PageResponse;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceConflictException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.project.api.CreateProjectRequest;
import com.pesaguard.backend.project.api.ProjectView;
import com.pesaguard.backend.project.api.TransferProjectOwnershipRequest;
import com.pesaguard.backend.project.api.UpdateProjectRequest;
import com.pesaguard.backend.project.domain.ProjectMember;
import com.pesaguard.backend.project.domain.ProjectMemberRole;
import com.pesaguard.backend.project.domain.ProjectSettings;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.project.domain.Project;
import com.pesaguard.backend.project.domain.ProjectStatus;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.events.application.DeveloperEventEmitter;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final AuditService auditService;
    private final ProjectAuthorization authorization;
    private final ProjectMetadataValidator metadataValidator;
    private final com.pesaguard.backend.rbac.application.AuthorizationService authorizationService;
    private final com.pesaguard.backend.project.infrastructure.ProjectSettingsRepository settingsRepository;
    private final com.pesaguard.backend.project.infrastructure.ProjectMemberRepository memberRepository;
    private final com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository
            organizationMembershipRepository;
    private final java.time.Clock clock;
    private final DeveloperEventEmitter developerEventEmitter;

    public ProjectService(
            ProjectRepository projectRepository,
            AuditService auditService,
            ProjectAuthorization authorization,
            ProjectMetadataValidator metadataValidator,
            com.pesaguard.backend.rbac.application.AuthorizationService authorizationService,
            com.pesaguard.backend.project.infrastructure.ProjectSettingsRepository settingsRepository,
            com.pesaguard.backend.project.infrastructure.ProjectMemberRepository memberRepository,
            com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository
                    organizationMembershipRepository,
            java.time.Clock clock,
            DeveloperEventEmitter developerEventEmitter) {
        this.projectRepository = projectRepository;
        this.auditService = auditService;
        this.authorization = authorization;
        this.metadataValidator = metadataValidator;
        this.authorizationService = authorizationService;
        this.settingsRepository = settingsRepository;
        this.memberRepository = memberRepository;
        this.organizationMembershipRepository = organizationMembershipRepository;
        this.clock = clock;
        this.developerEventEmitter = developerEventEmitter;
    }

    @Transactional
    public ProjectView create(AuthenticatedUser principal, CreateProjectRequest request) {
        authorizationService.requirePermission(principal, Permission.PROJECT_CREATE);
        if (projectRepository.existsByOrganizationIdAndSlug(
                principal.organizationId(), request.slug())) {
            throw new ResourceConflictException("PROJECT_SLUG_EXISTS", "A project with this slug already exists.");
        }
        Project project = projectRepository.saveAndFlush(Project.create(
                principal.organizationId(), request.name().trim(), request.slug(), principal.userId(),
                clock.instant()));
        settingsRepository.saveAndFlush(ProjectSettings.defaults(
                project.getId(), principal.organizationId(), clock.instant()));
        memberRepository.saveAndFlush(ProjectMember.add(
                project.getId(), principal.organizationId(), principal.userId(),
                ProjectMemberRole.MANAGER, principal.userId()));
        auditService.append(
                principal.organizationId(), principal.userId(), "project.created", "project",
                project.getId().toString(), RequestContext.currentRequestId(), Map.of("slug", project.getSlug()));
        developerEventEmitter.projectCreated(project);
        return toView(project);
    }

    @Transactional(readOnly = true)
    public ProjectView get(AuthenticatedUser principal, UUID projectId) {
        authorizationService.requirePermission(principal, Permission.PROJECT_READ);
        Project project = find(principal, projectId);
        authorization.requireProjectRead(principal, projectId);
        return toView(project);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectView> list(AuthenticatedUser principal, int page, int size) {
        authorizationService.requirePermission(principal, Permission.PROJECT_READ);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        PageRequest pageable = PageRequest.of(safePage, safeSize);
        Page<Project> projects = authorization.isOrganizationManager(principal)
                ? projectRepository.findByOrganizationIdOrderByCreatedAtDesc(
                        principal.organizationId(), pageable)
                : projectRepository.findVisibleToProjectMember(
                        principal.organizationId(), principal.userId(), pageable);
        Page<ProjectView> result = projects.map(this::toView);
        return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional
    public ProjectView archive(AuthenticatedUser principal, UUID projectId) {
        authorizationService.requirePermission(principal, Permission.PROJECT_DELETE);
        Project project = find(principal, projectId);
        authorization.requireProjectManage(principal, projectId);
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            return toView(project);
        }
        project.archive(clock.instant());
        projectRepository.saveAndFlush(project);
        developerEventEmitter.projectUpdated(project, java.util.List.of("status"));
        audit(principal, "project.archived", projectId, Map.of("status", ProjectStatus.ARCHIVED.name()));
        return toView(project);
    }

    private Project find(AuthenticatedUser principal, UUID projectId) {
        return projectRepository.findByIdAndOrganizationId(projectId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Project"));
    }

    @Transactional
    public ProjectView update(AuthenticatedUser principal, UUID projectId, UpdateProjectRequest request) {
        authorizationService.requirePermission(principal, Permission.PROJECT_UPDATE);
        Project project = find(principal, projectId);
        authorization.requireProjectManage(principal, projectId);
        requireActive(project);
        metadataValidator.validate(request.metadata());
        project.update(request.name().trim(), request.description(), request.metadata());
        projectRepository.saveAndFlush(project);
        developerEventEmitter.projectUpdated(project, java.util.List.of("name", "description", "metadata"));
        audit(principal, "project.updated", projectId, Map.of());
        return toView(project);
    }

    @Transactional
    public ProjectView restore(AuthenticatedUser principal, UUID projectId) {
        authorizationService.requirePermission(principal, Permission.PROJECT_UPDATE);
        Project project = find(principal, projectId);
        authorization.requireProjectManage(principal, projectId);
        project.restore(clock.instant());
        projectRepository.saveAndFlush(project);
        developerEventEmitter.projectUpdated(project, java.util.List.of("status"));
        audit(principal, "project.restored", projectId, Map.of("status", project.getStatus().name()));
        return toView(project);
    }

    @Transactional
    public ProjectView deactivate(AuthenticatedUser principal, UUID projectId) {
        authorizationService.requirePermission(principal, Permission.PROJECT_UPDATE);
        Project project = find(principal, projectId);
        authorization.requireProjectManage(principal, projectId);
        project.deactivate(clock.instant());
        projectRepository.saveAndFlush(project);
        developerEventEmitter.projectUpdated(project, java.util.List.of("status"));
        audit(principal, "project.deactivated", projectId, Map.of("status", project.getStatus().name()));
        return toView(project);
    }

    @Transactional
    public ProjectView activate(AuthenticatedUser principal, UUID projectId) {
        authorizationService.requirePermission(principal, Permission.PROJECT_UPDATE);
        Project project = find(principal, projectId);
        authorization.requireProjectManage(principal, projectId);
        project.activate(clock.instant());
        projectRepository.saveAndFlush(project);
        developerEventEmitter.projectUpdated(project, java.util.List.of("status"));
        audit(principal, "project.activated", projectId, Map.of("status", project.getStatus().name()));
        return toView(project);
    }

    /**
     * Transfers project ownership to an active organization member. The new owner
     * must already belong to the organization: ownership is not a way to grant
     * organization access.
     */
    @Transactional
    public ProjectView transferOwnership(AuthenticatedUser principal, UUID projectId,
            TransferProjectOwnershipRequest request) {
        authorizationService.requirePermission(principal, Permission.PROJECT_UPDATE);
        Project project = find(principal, projectId);
        authorization.requireProjectManage(principal, projectId);
        if (!project.getOwnerUserId().equals(principal.userId())) {
            throw new BusinessException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "PROJECT_OWNER_REQUIRED",
                    "Only the current project owner can transfer ownership.");
        }
        organizationMembershipRepository.findByOrganizationIdAndUserId(principal.organizationId(), request.userId())
                .filter(membership -> membership.getStatus()
                        == com.pesaguard.backend.organization.domain.MembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "PROJECT_OWNER_NOT_A_MEMBER",
                        "The new project owner must be an active member of the organization."));
        project.transferOwnership(request.userId());
        projectRepository.saveAndFlush(project);
        developerEventEmitter.projectUpdated(project, java.util.List.of("ownerUserId"));
        audit(principal, "project.ownership_transferred", projectId, Map.of("newOwnerUserId", request.userId().toString()));
        return toView(project);
    }

    private void requireActive(Project project) {
        if (!project.getStatus().acceptsChanges()) {
            throw new BusinessException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "PROJECT_NOT_ACTIVE",
                    "Only an active project accepts changes.");
        }
    }

    private void audit(AuthenticatedUser principal, String action, UUID projectId, Map<String, ?> metadata) {
        auditService.append(principal.organizationId(), principal.userId(), action, "project",
                projectId.toString(), RequestContext.currentRequestId(), metadata);
    }

    private ProjectView toView(Project project) {
        return new ProjectView(project.getId(), project.getName(), project.getSlug(),
                project.getDescription(), project.getStatus(), project.getOwnerUserId(),
                project.getMetadata(), project.getStatusChangedAt(),
                project.getCreatedAt(), project.getUpdatedAt());
    }
}
