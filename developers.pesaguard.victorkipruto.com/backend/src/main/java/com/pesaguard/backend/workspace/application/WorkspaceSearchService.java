package com.pesaguard.backend.workspace.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.http.HttpStatus;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.analytics.application.RequestObservabilityService;
import com.pesaguard.backend.project.application.ProjectAccessScope;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.webhooks.infrastructure.WebhookEndpointRepository;

@Service
public class WorkspaceSearchService {

    private final ProjectAccessScope projectAccessScope;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final AuthorizationService authorizationService;
    private final ApiKeyRepository apiKeyRepository;
    private final WebhookEndpointRepository webhookRepository;
    private final RequestObservabilityService observabilityService;

    public WorkspaceSearchService(ProjectAccessScope projectAccessScope,
            ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository,
            AuthorizationService authorizationService,
            ApiKeyRepository apiKeyRepository,
            WebhookEndpointRepository webhookRepository,
            RequestObservabilityService observabilityService) {
        this.projectAccessScope = projectAccessScope;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.authorizationService = authorizationService;
        this.apiKeyRepository = apiKeyRepository;
        this.webhookRepository = webhookRepository;
        this.observabilityService = observabilityService;
    }

    @Transactional(readOnly = true)
    public List<SearchResult> search(AuthenticatedUser principal, String query) {
        if (principal == null || query == null || query.trim().length() < 2 || query.length() > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SEARCH_QUERY_INVALID",
                    "Search text must contain between 2 and 100 characters.");
        }
        String normalized = query.trim();
        var scope = projectAccessScope.resolve(principal, null, null);
        if (scope.isEmpty()) return List.of();

        List<SearchResult> results = new ArrayList<>();
        if (authorizationService.hasPermission(principal, Permission.PROJECT_READ)) {
            var projects = scope.projectIds() == null
                    ? projectRepository.searchOrganizationProjects(principal.organizationId(),
                            normalized, PageRequest.of(0, 6))
                    : projectRepository.searchVisibleProjects(principal.organizationId(),
                            normalized, scope.projectIds(), PageRequest.of(0, 6));
            projects
                    .forEach(project -> results.add(new SearchResult(project.getId().toString(),
                            "PROJECT", project.getName(), project.getSlug(), null)));
        }
        if (authorizationService.hasPermission(principal, Permission.ENVIRONMENT_READ)) {
            var environments = scope.projectIds() == null
                    ? environmentRepository.searchOrganizationEnvironments(principal.organizationId(),
                            normalized, PageRequest.of(0, 6))
                    : environmentRepository.searchVisibleEnvironments(principal.organizationId(),
                            scope.projectIds(), normalized, PageRequest.of(0, 6));
            environments
                    .forEach(environment -> results.add(new SearchResult(environment.getId().toString(),
                            "ENVIRONMENT", environment.getName(), environment.getType().name(),
                            environment.getProjectId().toString())));
        }
        if (authorizationService.hasPermission(principal, Permission.CREDENTIAL_READ)) {
            var now = java.time.Instant.now();
            var keys = scope.projectIds() == null
                    ? apiKeyRepository.searchOrganizationKeys(principal.organizationId(),
                            normalized, now, PageRequest.of(0, 6))
                    : apiKeyRepository.searchVisibleKeys(principal.organizationId(), scope.projectIds(),
                            normalized, now, PageRequest.of(0, 6));
            keys
                    .stream()
                    .filter(key -> key.getStatus() == com.pesaguard.backend.credentials.api.ApiKeyStatus.ACTIVE
                            || key.getStatus() == com.pesaguard.backend.credentials.api.ApiKeyStatus.SUSPENDED)
                    .forEach(key -> results.add(new SearchResult(key.getId().toString(),
                            "API_KEY", key.getName(), key.getStatus().name(),
                            key.getProjectId().toString())));
        }
        if (authorizationService.hasPermission(principal, Permission.WEBHOOK_READ)) {
            var endpoints = scope.projectIds() == null
                    ? webhookRepository.searchOrganizationEndpoints(principal.organizationId(),
                            normalized, PageRequest.of(0, 6))
                    : webhookRepository.searchVisibleEndpoints(principal.organizationId(), scope.projectIds(),
                            normalized, PageRequest.of(0, 6));
            endpoints
                    .forEach(endpoint -> results.add(new SearchResult(endpoint.getId().toString(),
                            "WEBHOOK", endpoint.getName(), endpoint.getStatus(),
                            endpoint.getProjectId().toString())));
        }
        if (authorizationService.hasPermission(principal, Permission.USAGE_READ)
                && normalized.length() >= 12
                && normalized.matches("[A-Za-z0-9._:-]{12,64}")) {
            observabilityService.search(principal, null, null, null, null, normalized,
                            null, null, 0, 5)
                    .forEach(log -> results.add(new SearchResult(log.requestId(), "LOG",
                            log.requestId(), log.method() + " " + log.endpoint() + " · " + log.statusCode(),
                            log.projectId() == null ? null : log.projectId().toString())));
        }
        return List.copyOf(results);
    }

    public record SearchResult(String id, String type, String label, String detail, String projectId) {
    }
}
