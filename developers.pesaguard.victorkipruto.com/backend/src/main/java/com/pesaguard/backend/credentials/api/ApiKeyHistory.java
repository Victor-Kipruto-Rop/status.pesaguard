package com.pesaguard.backend.credentials.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Append-only record of an API key state change. A database trigger rejects
 * updates and deletes, so lifecycle history cannot be rewritten.
 */
@Entity
@Table(name = "api_key_history")
public class ApiKeyHistory {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "api_key_id", nullable = false)
    private UUID apiKeyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 24)
    private ApiKeyStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 24)
    private ApiKeyStatus toStatus;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApiKeyHistory() {
    }

    private ApiKeyHistory(UUID organizationId, UUID apiKeyId, ApiKeyStatus fromStatus,
            ApiKeyStatus toStatus, String action, UUID actorUserId, String reason, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.apiKeyId = apiKeyId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.action = action;
        this.actorUserId = actorUserId;
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();
        this.createdAt = now;
    }

    public static ApiKeyHistory record(UUID organizationId, UUID apiKeyId, ApiKeyStatus fromStatus,
            ApiKeyStatus toStatus, String action, UUID actorUserId, String reason, Instant now) {
        return new ApiKeyHistory(organizationId, apiKeyId, fromStatus, toStatus, action, actorUserId, reason, now);
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public ApiKeyStatus getFromStatus() { return fromStatus; }
    public ApiKeyStatus getToStatus() { return toStatus; }
    public String getAction() { return action; }
    public UUID getActorUserId() { return actorUserId; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}