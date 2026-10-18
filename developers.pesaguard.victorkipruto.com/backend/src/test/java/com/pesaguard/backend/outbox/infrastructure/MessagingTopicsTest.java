package com.pesaguard.backend.outbox.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Topic routing.
 *
 * <p>The routing table is the boundary between the Developer Platform and every
 * other PesaGuard domain sharing the broker, so these tests are about
 * containment rather than convenience.
 */
class MessagingTopicsTest {

    private final MessagingTopics topics = new MessagingTopics();

    @Test
    void credentialEventsShareOneTopicSoASubscriptionCanCoverThemAll() {
        // An integrator subscribing to credential lifecycle expects one
        // subscription to deliver every kind of credential event.
        assertThat(topics.topicFor("developer.api_key.created"))
                .isEqualTo(topics.topicFor("developer.oauth_app.created"))
                .isEqualTo(MessagingTopics.DEVELOPER_PREFIX + "credentials");
    }

    @Test
    void anEventFromAnotherDomainIsRefused() {
        // The broker also carries pesaguard.transaction.* and pesaguard.fraud.*
        // Those belong to other services. Publishing here would put a Developer
        // Platform producer inside another domain's topic.
        assertThatThrownBy(() -> topics.topicFor("transaction.ingested"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the developer namespace");

        assertThatThrownBy(() -> topics.topicFor("pesaguard.fraud.detected"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownDeveloperEventIsRefusedRatherThanDefaulted() {
        // Defaulting would publish to a catch-all topic where the event sat
        // unclaimed, discovered only when an integrator reported it missing.
        assertThatThrownBy(() -> topics.topicFor("developer.something.new"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No topic is configured");
    }

    @Test
    void aMissingEventTypeIsRefused() {
        assertThatThrownBy(() -> topics.topicFor(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> topics.topicFor("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyTopicStaysInsideTheDeveloperNamespace() {
        String[] eventTypes = {
                "developer.project.created", "developer.environment.promoted",
                "developer.webhook.delivery.failed", "developer.sandbox.created",
                "developer.security_event.detected", "developer.quota.exceeded",
                "developer.production_access.requested", "developer.member.added"};

        for (String eventType : eventTypes) {
            assertThat(topics.topicFor(eventType))
                    .as("topic for %s", eventType)
                    .startsWith(MessagingTopics.DEVELOPER_PREFIX);
        }
    }

    @Test
    void projectAndEnvironmentShareATopicToPreserveOrdering() {
        // An environment promotion and the project it belongs to must arrive in
        // commit order for a consumer; separate topics would break that.
        assertThat(topics.topicFor("developer.project.updated"))
                .isEqualTo(topics.topicFor("developer.environment.promoted"));
    }

    @Test
    void publishabilityAgreesWithTopicResolution() {
        assertThat(topics.isPublishable("developer.project.created")).isTrue();
        assertThat(topics.isPublishable("transaction.ingested")).isFalse();
        assertThat(topics.isPublishable("developer.unknown.event")).isFalse();
    }
}
