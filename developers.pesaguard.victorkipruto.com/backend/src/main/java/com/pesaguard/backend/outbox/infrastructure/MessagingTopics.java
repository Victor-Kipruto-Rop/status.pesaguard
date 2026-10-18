package com.pesaguard.backend.outbox.infrastructure;

import java.util.Map;

/**
 * Chooses which topic an event type is published to.
 *
 * <p><b>Owns a separate namespace from the rest of PesaGuard.</b> The shared
 * broker already carries {@code pesaguard.transaction.*},
 * {@code pesaguard.fraud.*} and {@code pesaguard.notification.*}, which belong to
 * other domains and are declared out of scope here. Developer Platform events use
 * the {@code pesaguard.developer.*} prefix so the two can never be confused,
 * and so a consumer's subscription to developer events cannot accidentally
 * receive financial traffic.
 *
 * <p>Unknown types are refused rather than defaulted. A typo in an event name
 * would otherwise be published to a default topic, where it would sit unclaimed
 * and be discovered only when an integrator reported a missing event. Failing at
 * publish time means the dead-letter row is visible immediately.
 */
public class MessagingTopics {

    /** Prefix owned exclusively by the Developer Platform. */
    public static final String DEVELOPER_PREFIX = "pesaguard.developer.";

    /** Dead letters from this platform. Separate from other domains' DLQs. */
    public static final String DEVELOPER_DLQ = "pesaguard.developer.dlq";

    /**
     * Explicit routing for event types whose topic is not simply the name.
     *
     * <p>Grouped by subject rather than one topic per event type: an integrator
     * subscribing to credential lifecycle wants every credential event, and
     * splitting them across topics would make that subscription impossible to
     * express.
     *
     * <p>{@code ofEntries} rather than {@code of}: there are more than ten routes,
     * and {@code Map.of} has a ten-pair limit that fails at compile time.
     */
    private static final Map<String, String> TOPIC_BY_PREFIX = Map.ofEntries(
            Map.entry("developer.credential.", DEVELOPER_PREFIX + "credentials"),
            Map.entry("developer.api_key.", DEVELOPER_PREFIX + "credentials"),
            Map.entry("developer.oauth_app.", DEVELOPER_PREFIX + "credentials"),
            Map.entry("developer.webhook.", DEVELOPER_PREFIX + "webhooks"),
            Map.entry("developer.event.", DEVELOPER_PREFIX + "events"),
            Map.entry("developer.security_event.", DEVELOPER_PREFIX + "security"),
            Map.entry("developer.organization.", DEVELOPER_PREFIX + "organization"),
            Map.entry("developer.member.", DEVELOPER_PREFIX + "organization"),
            Map.entry("developer.project.", DEVELOPER_PREFIX + "project"),
            Map.entry("developer.environment.", DEVELOPER_PREFIX + "project"),
            Map.entry("developer.sandbox.", DEVELOPER_PREFIX + "sandbox"),
            Map.entry("developer.quota.", DEVELOPER_PREFIX + "quotas"),
            Map.entry("developer.production_access.", DEVELOPER_PREFIX + "production-access"));

    /**
     * Topic for an event type.
     *
     * @throws IllegalArgumentException for a type outside the developer namespace.
     * Refusing is deliberate: silently publishing a misrouted event would make
     * the misrouting invisible until an integrator noticed a missing webhook.
     */
    public String topicFor(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("An event type is required");
        }
        if (!eventType.startsWith("developer.")) {
            // The outbox must only ever carry Developer Platform events. Anything
            // else means a caller reached across a domain boundary by mistake.
            throw new IllegalArgumentException(
                    "Event type is outside the developer namespace: " + eventType);
        }
        for (Map.Entry<String, String> route : TOPIC_BY_PREFIX.entrySet()) {
            if (eventType.startsWith(route.getKey())) {
                return route.getValue();
            }
        }
        throw new IllegalArgumentException("No topic is configured for event type: " + eventType);
    }

    /** Whether an event type can be published at all. */
    public boolean isPublishable(String eventType) {
        try {
            topicFor(eventType);
            return true;
        } catch (IllegalArgumentException unroutable) {
            return false;
        }
    }
}