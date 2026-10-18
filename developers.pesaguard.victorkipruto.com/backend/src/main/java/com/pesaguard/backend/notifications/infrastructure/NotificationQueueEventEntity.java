package com.pesaguard.backend.notifications.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.pesaguard.backend.notifications.domain.NotificationSeverity;
import com.pesaguard.backend.notifications.domain.NotificationType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Durable work item written in the same transaction as the event's source change. */
@Entity
@Table(name = "notification_event_queue")
public class NotificationQueueEventEntity {

    public enum State {
        PENDING,
        PROCESSED,
        DEAD_LETTER
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 48)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private NotificationSeverity severity;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Column(name = "resource_type", length = 80)
    private String resourceType;

    @Column(name = "resource_id", length = 160)
    private String resourceId;

    @Column(name = "action_url", length = 1000)
    private String actionUrl;

    @Column(name = "deduplication_key", length = 200)
    private String deduplicationKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state = State.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected NotificationQueueEventEntity() {
    }

    public NotificationQueueEventEntity(UUID id, UUID organizationId, UUID userId,
            NotificationType type, NotificationSeverity severity, String subject, String body,
            String resourceType, String resourceId, String actionUrl, String deduplicationKey,
            Instant availableAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.userId = userId;
        this.type = type;
        this.severity = severity;
        this.subject = subject;
        this.body = body;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.actionUrl = actionUrl;
        this.deduplicationKey = deduplicationKey;
        this.availableAt = availableAt;
    }

    public void markProcessed(Instant at) {
        state = State.PROCESSED;
        processedAt = at;
        lastError = null;
    }

    public void recordFailure(String error, Instant retryAt, int maxAttempts) {
        attempts++;
        lastError = error == null ? "Processing failed" : error.substring(0, Math.min(error.length(), 500));
        if (attempts >= maxAttempts) {
            state = State.DEAD_LETTER;
        } else {
            availableAt = retryAt;
        }
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public NotificationType getType() { return type; }
    public NotificationSeverity getSeverity() { return severity; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getActionUrl() { return actionUrl; }
    public State getState() { return state; }
    public int getAttempts() { return attempts; }
}
