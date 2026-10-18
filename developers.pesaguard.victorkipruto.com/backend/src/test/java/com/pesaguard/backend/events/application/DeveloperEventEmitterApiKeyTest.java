package com.pesaguard.backend.events.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.json.JsonMapper;

import com.pesaguard.backend.credentials.api.ApiKey;
import com.pesaguard.backend.events.domain.EventTypeDefinition;
import com.pesaguard.backend.events.infrastructure.EventTypeDefinitionRepository;
import com.pesaguard.backend.notifications.application.CredentialNotificationEmitter;
import com.pesaguard.backend.outbox.application.OutboxService;

class DeveloperEventEmitterApiKeyTest {

    @Test
    void suspendedKeyEventContainsMonotonicVersionAndSafeTenantContextOnly() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        ApiKey key = ApiKey.create(
                organizationId,
                projectId,
                environmentId,
                "event-test-key",
                "pgk_safe_prefix",
                "secret-hash-must-not-be-published",
                "read:analytics",
                Instant.parse("2027-01-01T00:00:00Z"),
                UUID.randomUUID());
        key.storeEncryptedSecret("encrypted-secret-must-not-be-published");
        key.activate();
        key.suspend(Instant.parse("2026-10-07T12:00:00Z"));
        ReflectionTestUtils.setField(key, "version", 7L);

        EventTypeDefinitionRepository eventTypes = mock(EventTypeDefinitionRepository.class);
        EventTypeDefinition definition = mock(EventTypeDefinition.class);
        when(eventTypes.findById("developer.api_key.suspended"))
                .thenReturn(Optional.of(definition));
        when(definition.shouldEmit(any(Instant.class))).thenReturn(true);
        when(definition.getVersion()).thenReturn(1);
        when(definition.getSchema()).thenReturn("""
                {"type":"object","required":["id","status","sourceVersion","organizationId","projectId","environmentId"]}
                """);
        OutboxService outbox = mock(OutboxService.class);
        DeveloperEventEmitter emitter = new DeveloperEventEmitter(
                eventTypes,
                outbox,
                JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC),
                mock(CredentialNotificationEmitter.class));

        emitter.apiKeySuspended(key);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).recordTenantEvent(
                eq("developer.api_key.suspended"),
                eq(1),
                payload.capture(),
                eq(organizationId),
                eq(projectId),
                eq(environmentId),
                nullable(String.class),
                nullable(String.class));
        var json = JsonMapper.builder().build().readTree(payload.getValue());
        org.assertj.core.api.Assertions.assertThat(json.path("id").asString())
                .isEqualTo(key.getId().toString());
        org.assertj.core.api.Assertions.assertThat(json.path("status").asString())
                .isEqualTo("SUSPENDED");
        org.assertj.core.api.Assertions.assertThat(json.path("sourceVersion").asLong())
                .isEqualTo(7L);
        org.assertj.core.api.Assertions.assertThat(json.path("organizationId").asString())
                .isEqualTo(organizationId.toString());
        org.assertj.core.api.Assertions.assertThat(payload.getValue())
                .doesNotContain("secret-hash-must-not-be-published")
                .doesNotContain("encrypted-secret-must-not-be-published")
                .doesNotContain("key_hash")
                .doesNotContain("encryptedSecret");
    }
}
