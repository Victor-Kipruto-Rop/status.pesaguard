package com.pesaguard.backend.project.application;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.environment.application.EnvironmentAccessPolicyService;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;
import com.pesaguard.backend.project.infrastructure.ProjectMemberRepository;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/** Resolves project and environment filters into an authorized data scope. */
@Service
public class ProjectAccessScope {

    private final ProjectAuthorization authorization;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final EnvironmentAccessPolicyService environmentAccessPolicyService;

    public ProjectAccessScope(ProjectAuthorization authorization,
            ProjectRepository projectRepository,
            ProjectMemberRepository memberRepository,
            ProjectEnvironmentRepository environmentRepository,
            EnvironmentAccessPolicyService environmentAccessPolicyService) {
        this.authorization = authorization;
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.environmentRepository = environmentRepository;
        this.environmentAccessPolicyService = environmentAccessPolicyService;
    }

    /**
     * Resolves explicit filters and the set of projects visible when a query is
     * aggregate. A null project set means an organization manager may see all
     * projects; an empty set means the caller currently has no project access.
     */
    @Transactional(readOnly = true)
    public Scope resolve(AuthenticatedUser principal, UUID projectId, UUID environmentId) {
        UUID resolvedProjectId = projectId;
        if (environmentId != null) {
            UUID environmentProjectId = environmentRepository
                    .findByIdAndOrganizationId(environmentId, principal.organizationId())
                    .map(environment -> environment.getProjectId())
                    .orElseThrow(() -> new ResourceNotFoundException("Environment"));
            if (resolvedProjectId != null && !resolvedProjectId.equals(environmentProjectId)) {
                throw new ResourceNotFoundException("Environment");
            }
            resolvedProjectId = environmentProjectId;
        }

        if (resolvedProjectId != null) {
            projectRepository.findByIdAndOrganizationId(resolvedProjectId, principal.organizationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Project"));
            authorization.requireProjectRead(principal, resolvedProjectId);
            if (environmentId != null) {
                requireEnvironmentAccess(principal, resolvedProjectId, environmentId, EnvironmentPermission.READ);
            } else if (!environmentAccessPolicyService.isOrganizationAdministratorRole(principal)
                    && environmentAccessPolicyService.hasPoliciesForProject(resolvedProjectId)) {
                throw environmentSelectionRequired();
            }
            return new Scope(Set.of(resolvedProjectId), resolvedProjectId, environmentId);
        }

        if (authorization.isOrganizationManager(principal)) {
            return new Scope(null, null, null);
        }
        Set<UUID> visibleProjects = memberRepository
                .findActiveByOrganizationIdAndUserId(principal.organizationId(), principal.userId())
                .stream()
                .map(member -> member.getProjectId())
                .collect(Collectors.toUnmodifiableSet());
        if (environmentAccessPolicyService.hasPoliciesForProjects(visibleProjects)) {
            throw environmentSelectionRequired();
        }
        return new Scope(visibleProjects, null, null);
    }

    private static BusinessException environmentSelectionRequired() {
        return new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "ENVIRONMENT_SELECTION_REQUIRED",
                "Select an environment to view data for projects with environment access policies.");
    }

    public void requireEnvironmentAccess(AuthenticatedUser principal, UUID projectId, UUID environmentId,
            EnvironmentPermission permission) {
        authorization.requireProjectRead(principal, projectId);
        var environment = environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        environmentId, principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        environmentAccessPolicyService.requireAccess(principal, environment, permission);
    }

    public record Scope(Set<UUID> projectIds, UUID projectId, UUID environmentId) {
        public boolean isEmpty() {
            return projectIds != null && projectIds.isEmpty();
        }
    }
}
