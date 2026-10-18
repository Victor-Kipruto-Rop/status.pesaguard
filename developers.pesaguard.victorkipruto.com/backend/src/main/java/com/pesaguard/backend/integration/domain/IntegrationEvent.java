package com.pesaguard.backend.integration.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "integration_events")
public class IntegrationEvent {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "integration_id", nullable = false)
    private UUID integrationId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "details", nullable = false, length = 1000)
    private String details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IntegrationEvent() {
    }

    private IntegrationEvent(UUID organizationId, UUID integrationId, UUID actorUserId,
            String eventType, UUID requestId, String details) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.integrationId = integrationId;
        this.actorUserId = actorUserId;
        this.eventType = eventType;
        this.requestId = requestId;
        this.details = details == null ? "" : details;
    }

    public static IntegrationEvent record(UUID organizationId, UUID integrationId, UUID actorUserId,
            String eventType, UUID requestId, String details) {
        return new IntegrationEvent(organizationId, integrationId, actorUserId, eventType, requestId, details);
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getIntegrationId() { return integrationId; }
    public UUID getActorUserId() { return actorUserId; }
    public String getEventType() { return eventType; }
    public UUID getRequestId() { return requestId; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
