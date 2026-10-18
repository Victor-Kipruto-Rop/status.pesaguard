package com.pesaguard.backend.status.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "platform_incident_updates")
public class PlatformIncidentUpdate {
    @Id private UUID id;
    @Column(name = "incident_id", nullable = false) private UUID incidentId;
    @Column(nullable = false, columnDefinition = "text") private String message;
    @Column(name = "public_visible", nullable = false) private boolean publicVisible;
    @Column(name = "actor_id", nullable = false) private UUID actorId;
    @Column(name = "actor_subject", nullable = false, length = 200) private String actorSubject;
    @Column(name = "action_reason", nullable = false, length = 500) private String actionReason;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected PlatformIncidentUpdate() { }

    public static PlatformIncidentUpdate record(UUID incidentId, String message, boolean publicVisible,
            UUID actorId, String actorSubject, String actionReason) {
        PlatformIncidentUpdate update = new PlatformIncidentUpdate();
        update.id = UUID.randomUUID();
        update.incidentId = incidentId;
        update.message = message.trim();
        update.publicVisible = publicVisible;
        update.actorId = actorId;
        update.actorSubject = actorSubject.trim();
        update.actionReason = actionReason;
        return update;
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public String getMessage() { return message; }
    public boolean isPublicVisible() { return publicVisible; }
    public Instant getCreatedAt() { return createdAt; }
}
