package com.pesaguard.backend.credentials.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.HttpServletRequest;
import com.pesaguard.backend.analytics.application.UsageQueryService;
import com.pesaguard.backend.analytics.application.UsageQueryService.UsageSeries;
import com.pesaguard.backend.audit.infrastructure.AuditEventRepository;
import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.events.application.EventPlatformService;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.project.domain.Project;
import com.pesaguard.backend.project.domain.ProjectStatus;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.securitycenter.application.SecurityEventService;
import com.pesaguard.backend.webhooks.application.WebhookEndpointService;

class ApiKeyDataControllerTest {

    private final ApiKeyAuthenticator authenticator = mock(ApiKeyAuthenticator.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final ProjectEnvironmentRepository environmentRepository = mock(ProjectEnvironmentRepository.class);
    private final UsageQueryService usageQueryService = mock(UsageQueryService.class);
    private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
    private final EventPlatformService eventPlatformService = mock(EventPlatformService.class);
    private final WebhookEndpointService webhookEndpointService = mock(WebhookEndpointService.class);
    private final SecurityEventService securityEventService = mock(SecurityEventService.class);
    private ApiKeyDataController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiKeyDataController(authenticator, organizationRepository,
                projectRepository, environmentRepository, usageQueryService, auditEventRepository,
                eventPlatformService, webhookEndpointService, securityEventService);
    }

    @Test
    void missingScopeIsRejectedBeforeDataIsQueried() {
        ApiKey key = key(Set.of("audit:read"));
        when(authenticator.authenticateKeyForRequest(eq("secret"), any())).thenReturn(Optional.of(key));
        MockHttpServletRequest request = request();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.usage(null, null, null, request));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(organizationRepository, projectRepository, environmentRepository,
                usageQueryService, auditEventRepository, eventPlatformService, webhookEndpointService);
        verify(authenticator, never()).recordUsage(any(ApiKey.class), any(HttpServletRequest.class));
    }

    @Test
    void usageQueryIsPinnedToTheApiKeysProjectAndEnvironment() {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        ApiKey key = key(organizationId, projectId, environmentId, Set.of("usage:read"));
        when(authenticator.authenticateKeyForRequest(eq("secret"), any())).thenReturn(Optional.of(key));
        Organization organization = mock(Organization.class);
        Project project = mock(Project.class);
        com.pesaguard.backend.environment.domain.ProjectEnvironment environment =
                mock(com.pesaguard.backend.environment.domain.ProjectEnvironment.class);
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(organization.getStatus()).thenReturn(OrganizationStatus.ACTIVE);
        when(projectRepository.findByIdAndOrganizationId(projectId, organizationId))
                .thenReturn(Optional.of(project));
        when(project.getStatus()).thenReturn(ProjectStatus.ACTIVE);
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                environmentId, organizationId, projectId)).thenReturn(Optional.of(environment));
        when(environment.getStatus()).thenReturn(EnvironmentStatus.ACTIVE);
        when(usageQueryService.series(any(), any(), any(), any(), eq(projectId), eq(environmentId)))
                .thenReturn(new UsageSeries(null, null, null, null, java.util.List.of()));
        MockHttpServletRequest request = request();

        controller.usage(null, null, null, request);

        verify(usageQueryService).series(any(), any(), any(), any(), eq(projectId), eq(environmentId));
        verify(authenticator).recordUsage(key, request);
    }

    @Test
    void contextRoutesOnlyReturnMetadataForTheAuthenticatedKey() {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        Set<String> scopes = Set.of(
                "organization:read", "project:read", "environment:read", "api:read");
        ApiKey key = key(organizationId, projectId, environmentId, scopes);
        when(authenticator.authenticateKeyForRequest(eq("secret"), any())).thenReturn(Optional.of(key));
        Organization organization = mock(Organization.class);
        Project project = mock(Project.class);
        com.pesaguard.backend.environment.domain.ProjectEnvironment environment =
                mock(com.pesaguard.backend.environment.domain.ProjectEnvironment.class);
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(organization.getStatus()).thenReturn(OrganizationStatus.ACTIVE);
        when(organization.getId()).thenReturn(organizationId);
        when(organization.getName()).thenReturn("Example");
        when(projectRepository.findByIdAndOrganizationId(projectId, organizationId))
                .thenReturn(Optional.of(project));
        when(project.getStatus()).thenReturn(ProjectStatus.ACTIVE);
        when(project.getId()).thenReturn(projectId);
        when(project.getName()).thenReturn("Payments");
        when(project.getOrganizationId()).thenReturn(organizationId);
        when(project.getCreatedAt()).thenReturn(createdAt);
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                environmentId, organizationId, projectId)).thenReturn(Optional.of(environment));
        when(environment.getStatus()).thenReturn(EnvironmentStatus.ACTIVE);
        when(environment.getId()).thenReturn(environmentId);
        when(environment.getName()).thenReturn("Production");
        when(environment.getType()).thenReturn(EnvironmentType.PRODUCTION);
        MockHttpServletRequest request = request();

        var organizationContext = controller.organization(request).data();
        var projectContext = controller.project(request).data();
        var environmentContext = controller.environment(request).data();
        var authenticationContext = controller.authenticationContext(request).data();

        assertEquals(organizationId, organizationContext.id());
        assertEquals("Example", organizationContext.name());
        assertEquals(projectId, projectContext.id());
        assertEquals(createdAt, projectContext.createdAt());
        assertEquals(environmentId, projectContext.environment().id());
        assertEquals("v1", projectContext.environment().apiVersion());
        assertEquals("production", environmentContext.type());
        assertEquals("v1", environmentContext.apiVersion());
        assertEquals(organizationId, authenticationContext.organizationId());
        assertEquals("live", authenticationContext.keyType());
        assertEquals(scopes.stream().sorted().toList(), authenticationContext.scopes());
        verify(projectRepository, times(4)).findByIdAndOrganizationId(projectId, organizationId);
        verify(environmentRepository, times(4)).findByIdAndOrganizationIdAndProjectId(
                environmentId, organizationId, projectId);
        verify(authenticator, times(4)).recordUsage(eq(key), any(HttpServletRequest.class));
    }

    private static ApiKey key(Set<String> scopes) {
        return key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), scopes);
    }

    private static ApiKey key(UUID organizationId, UUID projectId, UUID environmentId,
            Set<String> scopes) {
        ApiKey key = ApiKey.create(organizationId, projectId, environmentId, "test key",
                "pgk_test", "hash", ScopeCodec.encode(scopes), Instant.now().plusSeconds(3600),
                UUID.randomUUID());
        key.activate();
        return key;
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer secret");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
