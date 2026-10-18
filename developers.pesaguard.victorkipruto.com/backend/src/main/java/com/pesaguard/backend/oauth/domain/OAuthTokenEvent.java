package com.pesaguard.backend.oauth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Append-only record of a token lifecycle action. A trigger blocks updates/deletes. */
@Entity
@Table(name = "oauth_token_events")
public class OAuthTokenEvent {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "family_id")
    private UUID familyId;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OAuthTokenEvent() {
    }

    private OAuthTokenEvent(UUID organizationId, UUID applicationId, UUID familyId, String action,
            UUID actorUserId, String reason, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.applicationId = applicationId;
        this.familyId = familyId;
        this.action = action;
        this.actorUserId = actorUserId;
        this.reason = reason;
        this.createdAt = now;
    }

    public static OAuthTokenEvent record(UUID organizationId, UUID applicationId, UUID familyId,
            String action, UUID actorUserId, String reason, Instant now) {
        return new OAuthTokenEvent(organizationId, applicationId, familyId, action, actorUserId, reason, now);
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public String getAction() { return action; }
    public UUID getActorUserId() { return actorUserId; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}