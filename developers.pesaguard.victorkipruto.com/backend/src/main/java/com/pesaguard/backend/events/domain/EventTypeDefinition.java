package com.pesaguard.backend.events.domain;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One registered event type: its description, schema, version, category and
 * lifecycle.
 *
 * <p>Versioning exists so a subscriber can pin a payload shape. If
 * {@code developer.project.created} moves from v1 to v2 by gaining a field, a
 * subscriber pinned to v1 keeps receiving the v1 shape. Without pinning, a
 * routine additive change silently alters what integrators parse.
 *
 * <p>Deprecation is <b>not</b> removal. A deprecated event keeps being emitted
 * until its sunset date passes, because silently stopping an event breaks live
 * integrations and an outage is not a deprecation strategy. Retirement is gated on
 * the date, not on an operator deciding the event is unused.
 */
@Entity
@Table(name = "event_types")
public class EventTypeDefinition {

    @Id
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "namespace", nullable = false, length = 32)
    private String namespace;

    @Column(name = "entity", nullable = false, length = 32)
    private String entity;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 24)
    private EventCategory category;

    /** Pinned by subscribers. The schema for this version is what they receive. */
    @Column(name = "version", nullable = false)
    private int version;

    /** JSON Schema for this version's payload. */
    @Column(name = "schema", nullable = false, columnDefinition = "text")
    private String schema;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle", nullable = false, length = 24)
    private EventLifecycle lifecycle;

    /** Set when deprecated: the event to migrate to. Never the event itself. */
    @Column(name = "replaced_by", length = 128)
    private String replacedBy;

    /**
     * The last date the event is emitted. Enforced, not advisory: an operator
     * cannot retire an event whose sunset has not arrived.
     */
    @Column(name = "sunset_at")
    private Instant sunsetAt;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Version
    @Column(name = "version_lock", nullable = false)
    private long versionLock;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EventTypeDefinition() {
    }

    EventTypeDefinition(String name, String description, EventCategory category, int version,
            String schema, EventLifecycle lifecycle) {
        EventType parsed = EventType.tryParse(name).orElseThrow(() ->
                new IllegalArgumentException("Invalid event type name: " + name));
        this.name = name;
        this.namespace = parsed.namespace();
        this.entity = parsed.entity();
        this.action = parsed.action();
        this.description = Objects.requireNonNull(description, "description");
        this.category = Objects.requireNonNull(category, "category");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.lifecycle = lifecycle == null ? EventLifecycle.DRAFT : lifecycle;
        setVersion(version);
    }

    public EventType eventType() {
        return new EventType(namespace, entity, action);
    }

/**
     * Deprecates in favour of a replacement, with a mandatory sunset date.
     *
     * <p>The date is required. A deprecation with no end leaves subscribers
     * guessing whether the event is still coming.
     */
    public void deprecate(String replacement, Instant sunset, String reason) {
        Objects.requireNonNull(replacement, "replacement");
        if (replacement.equals(name)) {
            throw new IllegalArgumentException("An event cannot replace itself");
        }
        if (lifecycle == EventLifecycle.RETIRED) {
            throw new IllegalStateException("A retired event cannot be deprecated");
        }
        if (sunset == null) {
            throw new IllegalArgumentException("A sunset date is required to deprecate an event");
        }
        this.replacedBy = replacement;
        this.sunsetAt = sunset;
        this.changeReason = reason == null || reason.isBlank() ? null : reason.trim();
        this.lifecycle = EventLifecycle.DEPRECATED;
    }

    /**
     * Retires the event.
     *
     * <p>Refused until the sunset date has passed. This is the rule that stops an
     * operator from breaking live integrations by retiring an event early, however
     * confident they are that nobody subscribes to it.
     */
    public void retire(Instant now) {
        if (lifecycle == EventLifecycle.RETIRED) {
            return;
        }
        if (lifecycle != EventLifecycle.DEPRECATED) {
            throw new IllegalStateException("Only a deprecated event can be retired");
        }
        if (sunsetAt != null && now.isBefore(sunsetAt)) {
            throw new IllegalStateException(
                    "This event cannot be retired before its sunset date " + sunsetAt);
        }
        this.lifecycle = EventLifecycle.RETIRED;
    }

    /**
     * Whether the event should still be emitted at this moment.
     *
     * <p>A deprecated event whose sunset has passed stops being emitted even before
     * the retirement sweep runs, so a late sweep cannot keep an event alive past
     * the date its subscribers were promised.
     */
    public boolean shouldEmit(Instant now) {
        if (!lifecycle.isEmittable()) {
            return false;
        }
        if (lifecycle == EventLifecycle.DEPRECATED && sunsetAt != null && !now.isBefore(sunsetAt)) {
            return false;
        }
        return true;
    }

    /** Marks the event as ready to emit. */
    public void activate() {
        if (lifecycle != EventLifecycle.DRAFT) {
            throw new IllegalStateException("Only a draft event can be activated");
        }
        this.lifecycle = EventLifecycle.ACTIVE;
    }

    private void setVersion(int version) {
        if (version < 1) {
            throw new IllegalArgumentException("Event version must be at least 1");
        }
        this.version = version;
    }

    public String getName() { return name; }
    public String getNamespace() { return namespace; }
    public String getEntity() { return entity; }
    public String getAction() { return action; }
    public String getDescription() { return description; }
    public EventCategory getCategory() { return category; }
    public int getVersion() { return version; }
    public String getSchema() { return schema; }
    public EventLifecycle getLifecycle() { return lifecycle; }
    public String getReplacedBy() { return replacedBy; }
    public Instant getSunsetAt() { return sunsetAt; }
    public String getChangeReason() { return changeReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
