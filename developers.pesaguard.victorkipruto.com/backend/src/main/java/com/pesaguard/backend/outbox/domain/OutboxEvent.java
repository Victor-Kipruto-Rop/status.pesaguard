package com.pesaguard.backend.outbox.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An event committed to the database but not yet handed to the broker.
 *
 * <p>Written in the same transaction as the domain change it describes, which is
 * the entire point: a committed change always has a corresponding row here, and a
 * rolled-back change never does. Without that pairing, a crash between the two
 * writes loses the event while the domain change survives.
 *
 * <p>Delivery is <b>at-least-once</b>. A publish can succeed and the
 * {@code PUBLISHED} update can then fail, so the same event may reach a consumer
 * twice. That is why {@link #getEventId()} is immutable across retries: a
 * consumer that records the id can discard the replay. Exactly-once across
 * PostgreSQL and a broker would need a distributed transaction, which is
 * deliberately not used.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    /**
     * Stable public identity of the event, distinct from the row id.
     *
     * <p>Kept across retries so a republished event is recognisable as the same
     * event rather than a new one. The row id identifies the delivery attempt;
     * this identifies the thing being delivered.
     */
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OutboxStatus status;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "environment_id")
    private UUID environmentId;

    /**
     * Routing key, decided by the emitting domain.
     *
     * <p>Persisted rather than recomputed at publish time: deriving it later
     * would let a change to the derivation silently re-route events that are
     * already in flight, breaking per-tenant ordering.
     */
    @Column(name = "partition_key", nullable = false, length = 128)
    private String partitionKey;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    /**
     * The event body, stored as text and constrained to be valid JSON.
     *
     * <p>Held as a {@code String}. A {@code jsonb} column would be the natural
     * choice, but PostgreSQL refuses an implicit {@code varchar} to {@code jsonb}
     * conversion, so a String binding fails at the driver. Rather than add a JSON
     * library or a custom JdbcType for one field, the column is {@code text} with
     * a CHECK constraint that rejects anything that does not parse as JSON, which
     * preserves the guarantee that matters: a malformed payload cannot be stored.
     */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OutboxEvent() {
        // for JPA
    }
    private OutboxEvent(UUID id, UUID eventId, String eventType, int eventVersion,
            UUID organizationId, UUID projectId, UUID environmentId, String partitionKey,
            String correlationId, String traceId, String payload, String source,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("An event version starts at 1");
        }
        this.eventVersion = eventVersion;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.partitionKey = Objects.requireNonNull(partitionKey, "partitionKey");
        this.correlationId = correlationId;
        this.traceId = traceId;
        this.payload = Objects.requireNonNull(payload, "payload");
        this.source = Objects.requireNonNull(source, "source");
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Records an event for later publication.
     *
     * <p>{@code organizationId} and {@code projectId} are optional: platform-wide
     * events (a catalog version published, a schema deprecation) legitimately
     * belong to no tenant. Tenant-scoped events must supply them.
     */
    public static OutboxEvent record(UUID eventId, String eventType, int eventVersion,
            UUID organizationId, UUID projectId, String partitionKey,
            String correlationId, String traceId, String payload, String source, Instant now) {
        return new OutboxEvent(UUID.randomUUID(), eventId, eventType, eventVersion,
                organizationId, projectId, null, partitionKey, correlationId, traceId,
                payload, source, now);
    }

    public static OutboxEvent record(UUID eventId, String eventType, int eventVersion,
            UUID organizationId, UUID projectId, UUID environmentId, String partitionKey,
            String correlationId, String traceId, String payload, String source, Instant now) {
        return new OutboxEvent(UUID.randomUUID(), eventId, eventType, eventVersion,
                organizationId, projectId, environmentId, partitionKey, correlationId, traceId,
                payload, source, now);
    }

    /**
     * Marks the event as handed to the broker.
     *
     * @throws IllegalStateException if already published, so a double publish
     * cannot overwrite the original timestamp and hide when delivery happened
     */
    public void markPublished(Instant now) {
        if (status == OutboxStatus.PUBLISHED) {
            throw new IllegalStateException("Event was already published at " + publishedAt);
        }
        status = OutboxStatus.PUBLISHED;
        publishedAt = now;
        updatedAt = now;
        lastError = null;
    }

    /**
     * Records a failed attempt and schedules the next one.
     *
     * @param retryAt when the next attempt is permitted; the caller owns backoff
     */
    public void recordFailure(String error, Instant retryAt, Instant now) {
        requireNotPublished();
        attemptCount++;
        lastError = truncate(error);
        nextAttemptAt = retryAt;
        updatedAt = now;
    }

    /**
     * Stops retrying and parks the event for inspection.
     *
     * <p>The payload is retained deliberately: a dead letter nobody can read is
     * not a recovery capability. It is replayable once the cause is understood.
     */
    public void deadLetter(String error, Instant now) {
        requireNotPublished();
        status = OutboxStatus.DEAD_LETTERED;
        attemptCount++;
        lastError = truncate(error);
        updatedAt = now;
    }

    /**
     * Returns a dead-lettered event to the queue.
     *
     * <p>Used after an operator fixes the cause. The attempt counter resets,
     * because the previous attempts say nothing about whether the new attempt
     * will succeed, and keeping the old count would dead-letter it immediately
     * and make replay impossible.
     */
    public void requeue(Instant now) {
        if (status != OutboxStatus.DEAD_LETTERED) {
            throw new IllegalStateException("Only a dead-lettered event can be requeued");
        }
        status = OutboxStatus.PENDING;
        attemptCount = 0;
        nextAttemptAt = now;
        updatedAt = now;
    }

    private void requireNotPublished() {
        if (status == OutboxStatus.PUBLISHED) {
            throw new IllegalStateException("A published event cannot change afterwards");
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getEventVersion() {
        return eventVersion;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getPayload() {
        return payload;
    }

    public String getSource() {
        return source;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
