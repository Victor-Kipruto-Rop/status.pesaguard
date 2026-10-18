package com.pesaguard.backend.credentials.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.sandbox.domain.Sandbox;
import com.pesaguard.backend.sandbox.domain.SandboxStatus;
import com.pesaguard.backend.sandbox.infrastructure.SandboxRepository;
import com.pesaguard.backend.securitycenter.application.SecurityEventService;

import jakarta.servlet.http.HttpServletRequest;

class SandboxApiControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void acceptsReadScopedKeyOnlyForAnActiveSandboxAndRecordsUsage() {
        ApiKeyAuthenticator authenticator = mock(ApiKeyAuthenticator.class);
        ProjectEnvironmentRepository environmentRepository = mock(ProjectEnvironmentRepository.class);
        SandboxRepository sandboxRepository = mock(SandboxRepository.class);
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        ApiKey key = apiKey(organizationId, projectId, environmentId, Set.of("transactions:read"));
        ProjectEnvironment environment = mock(ProjectEnvironment.class);
        Sandbox sandbox = mock(Sandbox.class);
        HttpServletRequest request = mock(HttpServletRequest.class);

        when(request.getRemoteAddr()).thenReturn("192.0.2.10");
        when(authenticator.authenticateKeyForRequest("pgk_test", request)).thenReturn(Optional.of(key));
        when(environmentRepository.findByIdAndOrganizationIdAndProjectId(
                environmentId, organizationId, projectId)).thenReturn(Optional.of(environment));
        when(environment.getId()).thenReturn(environmentId);
        when(environment.getType()).thenReturn(EnvironmentType.SANDBOX);
        when(environment.getStatus()).thenReturn(EnvironmentStatus.ACTIVE);
        when(sandboxRepository.findByEnvironmentId(environmentId)).thenReturn(List.of(sandbox));
        when(sandbox.getStatus()).thenReturn(SandboxStatus.ACTIVE);
        when(sandbox.getExpiresAt()).thenReturn(NOW.plus(Duration.ofDays(1)));

        SandboxApiController controller = new SandboxApiController(authenticator, environmentRepository,
                sandboxRepository, Clock.fixed(NOW, ZoneOffset.UTC), mock(SecurityEventService.class));
        var response = controller.listTransactions("Bearer pgk_test", request);

        assertThat(response.data().sandbox()).isTrue();
        assertThat(response.data().data()).isEmpty();
        verify(authenticator).recordUsage(key, request);
    }

    @Test
    void rejectsKeysWithoutTheReadScopeBeforeRecordingUsage() {
        ApiKeyAuthenticator authenticator = mock(ApiKeyAuthenticator.class);
        ProjectEnvironmentRepository environmentRepository = mock(ProjectEnvironmentRepository.class);
        SandboxRepository sandboxRepository = mock(SandboxRepository.class);
        ApiKey key = apiKey(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Set.of("events:read"));
        HttpServletRequest request = mock(HttpServletRequest.class);

        when(request.getRemoteAddr()).thenReturn("192.0.2.10");
        when(authenticator.authenticateKeyForRequest("pgk_test", request)).thenReturn(Optional.of(key));
        SandboxApiController controller = new SandboxApiController(authenticator, environmentRepository,
                sandboxRepository, Clock.fixed(NOW, ZoneOffset.UTC), mock(SecurityEventService.class));

        assertThatThrownBy(() -> controller.listTransactions("Bearer pgk_test", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("transactions:read");
    }

    private ApiKey apiKey(UUID organizationId, UUID projectId, UUID environmentId, Set<String> scopes) {
        ApiKey key = ApiKey.create(organizationId, projectId, environmentId, "test", "pgk_test",
                "hash", ScopeCodec.encode(scopes), NOW.plus(Duration.ofDays(1)), UUID.randomUUID());
        key.activate();
        return key;
    }
}
