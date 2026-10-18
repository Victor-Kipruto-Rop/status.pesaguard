package com.pesaguard.backend.events.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.pesaguard.backend.events.domain.EventTypeDefinition;
import com.pesaguard.backend.events.infrastructure.EventTypeDefinitionRepository;
import com.pesaguard.backend.outbox.application.OutboxService;
import com.pesaguard.backend.project.domain.Project;
import com.pesaguard.backend.credentials.api.ApiKey;
import com.pesaguard.backend.webhooks.domain.WebhookEndpoint;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.notifications.application.CredentialNotificationEmitter;
import com.pesaguard.backend.rbac.domain.ProductionAccessRequest;

/** Emits registered developer events in the same transaction as their resource change. */
@Component
public class DeveloperEventEmitter {

    private final EventTypeDefinitionRepository eventTypes;
    private final OutboxService outbox;
    private final ObjectMapper objectMapper;
    private final java.time.Clock clock;
    private final CredentialNotificationEmitter credentialNotifications;

    public DeveloperEventEmitter(EventTypeDefinitionRepository eventTypes, OutboxService outbox,
            ObjectMapper objectMapper, java.time.Clock clock,
            CredentialNotificationEmitter credentialNotifications) {
        this.eventTypes = eventTypes;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.credentialNotifications = credentialNotifications;
    }

    @Transactional
    public void projectCreated(Project project) {
        emitProject(project, "developer.project.created", Map.of(
                "id", project.getId().toString(), "name", project.getName()));
    }

    @Transactional
    public void projectUpdated(Project project, List<String> changedFields) {
        emitProject(project, "developer.project.updated", Map.of(
                "id", project.getId().toString(), "changed", List.copyOf(changedFields)));
    }

    @Transactional
    public void apiKeyCreated(ApiKey key) {
        emitCredential(key, "developer.api_key.created", Map.of(
                "id", key.getId().toString(), "name", key.getName(),
                "status", key.getStatus().name(), "sourceVersion", key.getVersion()));
        credentialNotifications.apiKeyCreated(key);
    }

    @Transactional
    public void apiKeyRevoked(ApiKey key, String reason) {
        emitCredential(key, "developer.api_key.revoked", Map.of(
                "id", key.getId().toString(), "reason", reason,
                "status", key.getStatus().name(), "sourceVersion", key.getVersion()));
        credentialNotifications.apiKeyRevoked(key, reason);
    }

    @Transactional
    public void apiKeySuspended(ApiKey key) {
        emitCredential(key, "developer.api_key.suspended", Map.of(
                "id", key.getId().toString(), "status", key.getStatus().name(),
                "sourceVersion", key.getVersion()));
    }

    @Transactional
    public void apiKeyRotated(ApiKey previous, ApiKey replacement) {
        emitCredential(previous, "developer.api_key.revoked", Map.of(
                "id", previous.getId().toString(), "reason", "rotated",
                "status", previous.getStatus().name(), "sourceVersion", previous.getVersion()));
        emitCredential(replacement, "developer.api_key.created", Map.of(
                "id", replacement.getId().toString(), "name", replacement.getName(),
                "status", replacement.getStatus().name(), "sourceVersion", replacement.getVersion()));
        credentialNotifications.apiKeyRotated(previous);
    }

    @Transactional
    public void webhookCreated(WebhookEndpoint endpoint) {
        EventTypeDefinition definition = eventTypes.findById("developer.webhook.created")
                .filter(type -> type.shouldEmit(clock.instant()))
                .orElseThrow(() -> new IllegalStateException(
                        "The registered event is unavailable: developer.webhook.created"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", endpoint.getId().toString());
        payload.put("endpoint", endpoint.getUrl());
        validateRequiredFields(definition, payload);
        payload.put("organizationId", endpoint.getOrganizationId().toString());
        payload.put("projectId", endpoint.getProjectId().toString());
        payload.put("environmentId", endpoint.getEnvironmentId().toString());
        try {
            outbox.recordTenantEvent(definition.getName(), definition.getVersion(),
                    objectMapper.writeValueAsString(payload), endpoint.getOrganizationId(),
                    endpoint.getProjectId(), endpoint.getEnvironmentId(),
                    correlationId(), correlationId());
        } catch (JacksonException exception) {
            throw new IllegalStateException("The webhook event payload could not be serialized.", exception);
        }
    }

    @Transactional
    public void productionAccessChanged(ProductionAccessRequest request, String action) {
        if (!List.of("requested", "reviewed", "approved", "rejected", "activated",
                "suspended", "reactivated", "revoked", "cancelled").contains(action)) {
            throw new IllegalArgumentException("Unsupported production access event action.");
        }
        String eventName = "developer.production_access." + action;
        EventTypeDefinition definition = eventTypes.findById(eventName)
                .filter(type -> type.shouldEmit(clock.instant()))
                .orElseThrow(() -> new IllegalStateException(
                        "The registered event is unavailable: " + eventName));
        Map<String, Object> attributes = Map.of(
                "id", request.getId().toString(),
                "status", request.getStatus().name(),
                "environmentId", request.getEnvironmentId().toString());
        validateRequiredFields(definition, attributes);
        Map<String, Object> payload = new LinkedHashMap<>(attributes);
        payload.put("organizationId", request.getOrganizationId().toString());
        payload.put("projectId", request.getProjectId().toString());
        try {
            outbox.recordTenantEvent(eventName, definition.getVersion(),
                    objectMapper.writeValueAsString(payload), request.getOrganizationId(),
                    request.getProjectId(), request.getEnvironmentId(),
                    correlationId(), correlationId());
        } catch (JacksonException exception) {
            throw new IllegalStateException("The production access event payload could not be serialized.", exception);
        }
    }

    private void emitCredential(ApiKey key, String eventName, Map<String, Object> attributes) {
        EventTypeDefinition definition = eventTypes.findById(eventName)
                .filter(type -> type.shouldEmit(clock.instant()))
                .orElseThrow(() -> new IllegalStateException(
                        "The registered event is unavailable: " + eventName));
        Map<String, Object> payload = new LinkedHashMap<>(attributes);
        payload.put("organizationId", key.getOrganizationId().toString());
        payload.put("projectId", key.getProjectId().toString());
        payload.put("environmentId", key.getEnvironmentId().toString());
        validateRequiredFields(definition, payload);
        try {
            outbox.recordTenantEvent(eventName, definition.getVersion(), objectMapper.writeValueAsString(payload),
                    key.getOrganizationId(), key.getProjectId(), key.getEnvironmentId(),
                    correlationId(), correlationId());
        } catch (JacksonException exception) {
            throw new IllegalStateException("The registered event payload could not be serialized.", exception);
        }
    }

    private void emitProject(Project project, String eventName, Map<String, Object> attributes) {
        EventTypeDefinition definition = eventTypes.findById(eventName)
                .filter(type -> type.shouldEmit(clock.instant()))
                .orElseThrow(() -> new IllegalStateException(
                        "The registered event is unavailable: " + eventName));
        validateRequiredFields(definition, attributes);
        Map<String, Object> payload = new LinkedHashMap<>(attributes);
        payload.put("organizationId", project.getOrganizationId().toString());
        payload.put("projectId", project.getId().toString());
        try {
            outbox.recordTenantEvent(eventName, definition.getVersion(), objectMapper.writeValueAsString(payload),
                    project.getOrganizationId(), project.getId(),
                    correlationId(), correlationId());
        } catch (JacksonException exception) {
            throw new IllegalStateException("The registered event payload could not be serialized.", exception);
        }
    }

    private static String correlationId() {
        var requestId = RequestContext.currentRequestId();
        return requestId == null ? null : requestId.toString();
    }

    private void validateRequiredFields(EventTypeDefinition definition, Map<String, Object> payload) {
        try {
            JsonNode schema = objectMapper.readTree(definition.getSchema());
            JsonNode required = schema.get("required");
            if (required == null || !required.isArray()) {
                throw new IllegalStateException("The event schema has no required field list: "
                        + definition.getName());
            }
            for (JsonNode field : required) {
                if (!field.isTextual() || !payload.containsKey(field.asText())) {
                    throw new IllegalStateException("The event payload does not satisfy its registered schema: "
                            + definition.getName());
                }
            }
        } catch (JacksonException exception) {
            throw new IllegalStateException("The registered event schema is invalid: "
                    + definition.getName(), exception);
        }
    }
}
