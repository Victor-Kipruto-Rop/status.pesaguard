package com.pesaguard.backend.events.domain;

/**
 * How events are grouped for the catalog.
 *
 * <p>Category is presentation and review grouping, not a permission boundary. A
 * subscription to one event grants nothing else in its category.
 */
public enum EventCategory {
    /** Projects and environments. */
    PLATFORM("platform"),
    /** API keys and OAuth applications. */
    CREDENTIALS("credentials"),
    /** Webhook endpoints and their deliveries. */
    WEBHOOKS("webhooks"),
    /** Sandbox lifecycle. */
    SANDBOX("sandbox"),
    /** Access-control changes worth an audit trail. */
    SECURITY("security");

    private final String value;

    EventCategory(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}