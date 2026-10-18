package com.pesaguard.backend.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import com.pesaguard.backend.analytics.domain.ApiRequestEvent;
import com.pesaguard.backend.analytics.infrastructure.ApiRequestEventRepository;
import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.project.application.ProjectAccessScope;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

class RequestObservabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    private final ApiRequestEventRepository repository = mock(ApiRequestEventRepository.class);
    private final ProjectAccessScope projectAccessScope = mock(ProjectAccessScope.class);
    private final AuthorizationService authorizationService = mock(AuthorizationService.class);
    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
    private final RequestObservabilityService service = new RequestObservabilityService(
            repository, Clock.fixed(NOW, ZoneOffset.UTC), projectAccessScope, authorizationService, apiKeyRepository);

    @Test
    void searchIsScopedToTheAuthenticatedOrganizationAndOmitsRequestBodies() {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        AuthenticatedUser principal = principal(organizationId);
        when(projectAccessScope.resolve(principal, null, null))
                .thenReturn(new ProjectAccessScope.Scope(null, null, null));
        ApiRequestEvent event = ApiRequestEvent.record("request-123", organizationId,
                projectId, environmentId, null, null, "/v1/payments", "POST",
                201, 24, 128L, NOW.minusSeconds(60), null);
        when(repository.searchOrganizationRequests(eq(organizationId), eq(null), eq(null),
                eq(null), eq(null), eq(null), eq(null), any(Instant.class), eq(NOW), any()))
                .thenReturn(new PageImpl<>(List.of(event)));

        var result = service.search(principal, null, null, null, null, null,
                null, NOW, 0, 50);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().requestId()).isEqualTo("request-123");
        assertThat(result.getContent().getFirst().endpoint()).isEqualTo("/v1/payments");
        verify(repository).searchOrganizationRequests(eq(organizationId), eq(null), eq(null),
                eq(null), eq(null), eq(null), eq(null), any(Instant.class), eq(NOW), any());
    }

    @Test
    void searchCanBeRestrictedToOneApiKey() {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        AuthenticatedUser principal = principal(organizationId);
        when(apiKeyRepository.existsByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                apiKeyId, organizationId, projectId, environmentId)).thenReturn(true);
        when(projectAccessScope.resolve(principal, projectId, environmentId))
                .thenReturn(new ProjectAccessScope.Scope(Set.of(projectId), projectId, environmentId));
        when(repository.searchOrganizationRequestsForProjects(eq(organizationId), eq(Set.of(projectId)),
                eq(projectId), eq(environmentId), eq(null), eq(null), eq(null), eq(apiKeyId),
                any(Instant.class), eq(NOW), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.search(principal, projectId, environmentId, null, null, null,
                apiKeyId, null, NOW, 0, 50);

        verify(repository).searchOrganizationRequestsForProjects(eq(organizationId), eq(Set.of(projectId)),
                eq(projectId), eq(environmentId), eq(null), eq(null), eq(null), eq(apiKeyId),
                any(Instant.class), eq(NOW), any());
    }

    @Test
    void rejectsInvalidTimeRangeBeforeQuerying() {
        assertThatThrownBy(() -> service.search(principal(UUID.randomUUID()),
                null, null, null, null, null, NOW, NOW, 0, 50))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void requestDetailsCannotCrossTheSelectedEnvironment() {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID selectedEnvironmentId = UUID.randomUUID();
        UUID otherEnvironmentId = UUID.randomUUID();
        AuthenticatedUser principal = principal(organizationId);
        ApiRequestEvent event = ApiRequestEvent.record("request-123", organizationId,
                projectId, otherEnvironmentId, null, null, "/v1/payments", "POST",
                201, 24, 128L, NOW.minusSeconds(60), null);
        when(repository.findByRequestIdAndOrganizationId("request-123", organizationId))
                .thenReturn(java.util.Optional.of(event));
        when(projectAccessScope.resolve(principal, projectId, selectedEnvironmentId))
                .thenReturn(new ProjectAccessScope.Scope(Set.of(projectId), projectId, selectedEnvironmentId));

        assertThatThrownBy(() -> service.find(principal, "request-123", projectId, selectedEnvironmentId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("REQUEST_LOG_NOT_FOUND"));
        verify(repository).findByRequestIdAndOrganizationId("request-123", organizationId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void requestDetailsCannotCrossTheSelectedApiKey() {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID selectedKeyId = UUID.randomUUID();
        UUID otherKeyId = UUID.randomUUID();
        AuthenticatedUser principal = principal(organizationId);
        when(apiKeyRepository.existsByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                selectedKeyId, organizationId, projectId, environmentId)).thenReturn(true);
        ApiRequestEvent event = ApiRequestEvent.record("request-key", organizationId,
                projectId, environmentId, otherKeyId, null, "/v1/payments", "POST",
                201, 24, 128L, NOW.minusSeconds(60), null);
        when(repository.findByRequestIdAndOrganizationId("request-key", organizationId))
                .thenReturn(java.util.Optional.of(event));
        when(projectAccessScope.resolve(principal, projectId, environmentId))
                .thenReturn(new ProjectAccessScope.Scope(Set.of(projectId), projectId, environmentId));

        assertThatThrownBy(() -> service.find(principal, "request-key", projectId, environmentId, selectedKeyId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("REQUEST_LOG_NOT_FOUND"));
    }

    private AuthenticatedUser principal(UUID organizationId) {
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), organizationId, UUID.randomUUID(),
                "developer@example.test", "Developer", Set.of(Permission.USAGE_READ.value()));
        when(authorizationService.hasPermission(principal, Permission.USAGE_READ)).thenReturn(true);
        return principal;
    }
}
