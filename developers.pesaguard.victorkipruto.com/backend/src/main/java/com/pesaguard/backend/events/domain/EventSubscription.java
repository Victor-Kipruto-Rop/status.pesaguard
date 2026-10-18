package com.pesaguard.backend.events.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
 * A subscription: which events, at which version, delivered to which endpoint,
 * bound to which environment.
 *
 * <p>Filters narrow what is delivered. They can never widen it: a filter is
 * matched against the event's own attributes, and the subscription's tenant,
 * project and environment are fixed at creation and are not overridable by a
 * filter. That is what stops a subscriber writing a filter that reaches another
 * tenant's events.
 */
@Entity
@Table(name = "event_subscriptions")
public class EventSubscription {

    /**
     * Attribute keys a filter may reference. An allowlist rather than a free-form
     * map, because filters are evaluated on every event for every subscription: an
     * unbounded key set invites an expensive, unindexable predicate.
     */
    private static final Set<String> FILTERABLE_KEYS = Set.of(
            "projectId", "environmentId", "resourceType", "resourceId", "status");

    /**
     * Filter keys are an allowlist, and that allowlist is the real bound on filter
     * count: with five permitted keys a subscription can never hold more than five
     * filters, so a separate maximum would be unreachable code.
     */
    private static final int MAX_FILTERS = 5;
    private static final int MAX_FILTER_VALUE_LENGTH = 200;

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "endpoint_id", nullable = false, length = 128)
    private String endpointId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    /** Pinned payload version: a v2 event must not change a v1 subscriber's payload. */
    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(name = "filters", nullable = false, columnDefinition = "text")
    private String filters;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SubscriptionStatus status;

    @Column(name = "description", length = 500)
    private String description;

    @Version
    @Column(name = "version_lock", nullable = false)
    private long versionLock;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EventSubscription() {
    }

    private EventSubscription(UUID organizationId, UUID projectId, UUID environmentId,
            String endpointId, EventType eventType, int eventVersion, String description,
            Map<String, String> filters) {
        this.id = UUID.randomUUID();
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
        this.eventType = Objects.requireNonNull(eventType, "eventType").value();
        if (eventVersion < 1) {
            throw new IllegalArgumentException("Event version must be at least 1");
        }
        this.eventVersion = eventVersion;
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.filters = encodeFilters(filters);
        this.status = SubscriptionStatus.ACTIVE;
    }

    public static EventSubscription create(UUID organizationId, UUID projectId, UUID environmentId,
            String endpointId, EventType eventType, int eventVersion, String description,
            Map<String, String> filters) {
        return new EventSubscription(organizationId, projectId, environmentId, endpointId,
                eventType, eventVersion, description, filters);
    }

    /** Validates filters up front rather than failing silently at dispatch time. */
    public static void validateFilters(Map<String, String> filters) {
        encodeFilters(filters);
    }

    private static String encodeFilters(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        if (filters.size() > MAX_FILTERS) {
            throw new IllegalArgumentException("At most " + MAX_FILTERS + " filters are allowed");
        }
        Map<String, String> encoded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!FILTERABLE_KEYS.contains(key)) {
                throw new IllegalArgumentException("Filter key is not filterable: " + key);
            }
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.length() > MAX_FILTER_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "Filter value for " + key + " is too long");
            }
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Filter value for " + key + " must not be empty");
            }
            encoded.put(key, value);
        }
        return encoded.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .sorted()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    /** The filter set as a map. */
    public Map<String, String> filterSet() {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (filters == null || filters.isBlank()) {
            return parsed;
        }
        for (String line : filters.split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                parsed.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return parsed;
    }

    /**
     * Whether an event's attributes satisfy every filter.
     *
     * <p>All filters must match (AND). An event missing a filtered key does not
     * match: assuming a value would deliver events the subscriber did not ask for,
     * and a financial event delivered to the wrong webhook is not recoverable.
     */
    public boolean matches(Map<String, String> attributes) {
        for (Map.Entry<String, String> filter : filterSet().entrySet()) {
            String actual = attributes == null ? null : attributes.get(filter.getKey());
            if (actual == null || !actual.equals(filter.getValue())) {
                return false;
            }
        }
        return true;
    }

    public boolean matchesType(String candidateEventType) {
        return eventType.equals(candidateEventType);
    }

    /** A null environment accepts any; a pinned one accepts only its own. */
    public boolean matchesEnvironment(UUID candidateEnvironmentId) {
        return environmentId.equals(candidateEnvironmentId);
    }

    public void suspend() {
        if (status == SubscriptionStatus.SUSPENDED) {
            return;
        }
        if (status == SubscriptionStatus.CANCELLED) {
            throw new IllegalStateException("A cancelled subscription cannot be suspended");
        }
        status = SubscriptionStatus.SUSPENDED;
    }

    public void resume() {
        if (status == SubscriptionStatus.CANCELLED) {
            throw new IllegalStateException("A cancelled subscription cannot be resumed");
        }
        status = SubscriptionStatus.ACTIVE;
    }

    /** Terminal. */
    public void cancel() {
        status = SubscriptionStatus.CANCELLED;
    }

    public boolean delivers() {
        return status == SubscriptionStatus.ACTIVE;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public String getEndpointId() { return endpointId; }
    public String getEventType() { return eventType; }
    public int getEventVersion() { return eventVersion; }
    public String getFilters() { return filters; }
    public SubscriptionStatus getStatus() { return status; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}