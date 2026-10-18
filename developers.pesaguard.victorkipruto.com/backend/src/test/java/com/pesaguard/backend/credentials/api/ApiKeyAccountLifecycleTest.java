package com.pesaguard.backend.credentials.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.environment.infrastructure.EnvironmentLimitsRepository;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.environment.application.EnvironmentAccessPolicyService;
import com.pesaguard.backend.project.infrastructure.ProjectRepository;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.scopes.application.ScopeRegistryService;
import com.pesaguard.backend.security.authentication.IpRangeMatcher;

class ApiKeyAccountLifecycleTest {

    @Test
    void accountLifecycleRevokesNonterminalKeysAndAppendsHistoryAndAudit() {
        ApiKeyRepository keys = mock(ApiKeyRepository.class);
        ApiKeyHistoryRepository history = mock(ApiKeyHistoryRepository.class);
        AuditService audit = mock(AuditService.class);
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        ApiKey key = ApiKey.create(organizationId, UUID.randomUUID(), UUID.randomUUID(),
                "personal key", "pg_test_prefix", "secret-hash", "transactions:read",
                Instant.parse("2026-12-01T00:00:00Z"), userId);
        key.activate();
        when(keys.findByCreatedByOrderByCreatedAtDesc(userId)).thenReturn(List.of(key));

        ApiKeyService service = new ApiKeyService(
                keys,
                mock(ProjectRepository.class),
                mock(ProjectEnvironmentRepository.class),
                mock(ApiKeyGenerator.class),
                audit,
                mock(AuthorizationService.class),
                mock(EnvironmentLimitsRepository.class),
                history,
                mock(IpRangeMatcher.class),
                mock(ScopeRegistryService.class),
                mock(com.pesaguard.backend.project.application.ProjectAuthorization.class),
                mock(ApiKeyCreationIdempotencyRepository.class),
                mock(com.pesaguard.backend.security.credentials.SecretEncryptionService.class),
                mock(com.pesaguard.backend.events.application.DeveloperEventEmitter.class),
                mock(EnvironmentAccessPolicyService.class),
                mock(PipelineApiKeySynchronizer.class));

        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        assertThat(service.revokeCreatedByUserId(userId, now)).isEqualTo(1);
        assertThat(key.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(key.getRevokedAt()).isEqualTo(now);
        verify(keys).saveAndFlush(key);
        verify(history).save(any(ApiKeyHistory.class));
        verify(audit).append(eq(organizationId), eq(userId), eq("api_key.revoked"), eq("api_key"),
                eq(key.getId().toString()), any(), any());
    }
}
