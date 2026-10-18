package com.pesaguard.backend.events.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One delivery attempt: event to subscription to endpoint.
 *
 * <p>Append-only. Each attempt writes a new row rather than overwriting, so the
 * sequence of attempts, and what the endpoint said each time, survives. That is
 * what makes "how many times did this fail, and with what response?" answerable
 * afterwards.
 *
 * <p>The tenant is carried on the row rather than joined in. A delivery record
 * readable across tenants by a careless query would leak the fact that a given
 * resource changed, so the boundary is explicit rather than inferred.
 */
@Entity
@Table(name = "event_deliveries")
public class EventDelivery {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id")
    private UUID environmentId;

    @Column(name = "endpoint_id", nullable = false, length = 128)
    private String endpointId;

    /** 1-based. */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DeliveryStatus status;

    @Column(name = "response_code")
    private Integer responseCode;

    /**
     * A short, redacted excerpt of the endpoint's response. Excerpts only: a
     * delivery table must not become a second copy of integration payloads that
     * nobody thinks to apply retention to.
     */
    @Column(name = "response_excerpt", length = 512)
    private String responseExcerpt;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EventDelivery() {
    }

    private EventDelivery(UUID eventId, String eventType, UUID subscriptionId,
            UUID organizationId, UUID projectId, UUID environmentId, String endpointId,
            int attempt) {
        this.id = UUID.randomUUID();
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.environmentId = environmentId;
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
        if (attempt < 1) {
            throw new IllegalArgumentException("Attempt numbers start at 1");
        }
        this.attempt = attempt;
        this.status = DeliveryStatus.PENDING;
    }

    public static EventDelivery firstAttempt(UUID eventId, String eventType,
            EventSubscription subscription) {
        return new EventDelivery(eventId, eventType, subscription.getId(),
                subscription.getOrganizationId(), subscription.getProjectId(),
                subscription.getEnvironmentId(), subscription.getEndpointId(), 1);
    }

    /** The next attempt, preserving the attempt sequence. */
    public EventDelivery nextAttempt() {
        EventDelivery next = new EventDelivery(eventId, eventType, subscriptionId,
                organizationId, projectId, environmentId, endpointId, attempt + 1);
        next.status = DeliveryStatus.PENDING;
        return next;
    }

    public void markDelivered(int statusCode, int latencyMs) {
        this.status = DeliveryStatus.DELIVERED;
        this.responseCode = statusCode;
        this.latencyMs = Math.max(0, latencyMs);
        this.nextAttemptAt = null;
    }

    /**
     * Records a failed attempt and when the next one is due.
     *
     * @param deadLettered true when no attempts remain, moving the record to the
     *        DLQ rather than leaving it looking like a pending retry
     */
    public void markFailed(int statusCode, String errorCode, String responseExcerpt,
            int latencyMs, Instant nextAttemptAt, boolean deadLettered) {
        this.status = deadLettered ? DeliveryStatus.DEAD_LETTERED : DeliveryStatus.RETRY_SCHEDULED;
        this.responseCode = statusCode;
        this.errorCode = truncate(errorCode);
        this.responseExcerpt = truncate(responseExcerpt);
        this.latencyMs = Math.max(0, latencyMs);
        this.nextAttemptAt = deadLettered ? null : nextAttemptAt;
    }

    /** Records a non-retryable response without misclassifying it as a dead letter. */
    public void markPermanentlyFailed(int statusCode, String errorCode, int latencyMs) {
        this.status = DeliveryStatus.FAILED;
        this.responseCode = statusCode;
        this.errorCode = truncate(errorCode);
        this.latencyMs = Math.max(0, latencyMs);
        this.nextAttemptAt = null;
    }

    /** Suppressed because the subscription was not active. */
    public void markSuppressed(String reason) {
        this.status = DeliveryStatus.SUPPRESSED;
        this.errorCode = truncate(reason);
    }

    public void markInFlight() {
        this.status = DeliveryStatus.IN_FLIGHT;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public UUID getSubscriptionId() { return subscriptionId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public String getEndpointId() { return endpointId; }
    public int getAttempt() { return attempt; }
    public DeliveryStatus getStatus() { return status; }
    public Integer getResponseCode() { return responseCode; }
    public String getResponseExcerpt() { return responseExcerpt; }
    public String getErrorCode() { return errorCode; }
    public Integer getLatencyMs() { return latencyMs; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
}