package com.pesaguard.backend.events.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Subscriptions, and the filters that narrow delivery.
 *
 * <p>The property that matters most: filters can only narrow what is delivered.
 * The tenant, project and environment are fixed at creation and no filter can
 * widen them, so a subscriber cannot reach another tenant's events by crafting a
 * filter.
 */
class EventSubscriptionTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    private EventType projectCreated() {
        return EventType.tryParse("developer.project.created").orElseThrow();
    }

    private EventSubscription subscription(Map<String, String> filters) {
        return EventSubscription.create(organizationId, projectId, environmentId,
                "endpoint_1", projectCreated(), 1, "test", filters);
    }

    @Test
    void aNewSubscriptionIsActiveAndDelivers() {
        EventSubscription subscription = subscription(null);

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.delivers()).isTrue();
        assertThat(subscription.getEventVersion()).isEqualTo(1);
    }

    @Test
    void theVersionIsPinned() {
        // A v1 subscriber must keep receiving the v1 shape even after v2 exists,
        // or a routine additive change silently alters what integrators parse.
        assertThat(subscription(null).getEventVersion()).isEqualTo(1);
    }

    @Test
    void versionZeroIsRejected() {
        assertThatThrownBy(() -> EventSubscription.create(organizationId, projectId, environmentId,
                "endpoint_1", projectCreated(), 0, "bad", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnfilteredSubscriptionMatchesEverything() {
        EventSubscription subscription = subscription(null);

        assertThat(subscription.matches(Map.of())).isTrue();
        assertThat(subscription.matches(null)).isTrue();
    }

    @Test
    void aFilterMatchesOnlyItsExactValue() {
        EventSubscription subscription = subscription(Map.of("status", "ACTIVE"));

        assertThat(subscription.matches(Map.of("status", "ACTIVE"))).isTrue();
        assertThat(subscription.matches(Map.of("status", "DELETED"))).isFalse();
    }

    @Test
    void anEventMissingAFilteredKeyDoesNotMatch() {
        EventSubscription subscription = subscription(Map.of("resourceType", "project"));

        // Assuming a value would deliver events the subscriber did not ask for, and
        // a financial event delivered to the wrong webhook is not recoverable.
        assertThat(subscription.matches(Map.of("resourceType", "project"))).isTrue();
        assertThat(subscription.matches(Map.of("other", "project"))).isFalse();
        assertThat(subscription.matches(Map.of())).isFalse();
        assertThat(subscription.matches(null)).isFalse();
    }

    @Test
    void multipleFiltersAreAllRequired() {
        EventSubscription subscription = subscription(
                Map.of("resourceType", "project", "status", "ACTIVE"));

        assertThat(subscription.matches(Map.of("resourceType", "project", "status", "ACTIVE")))
                .isTrue();
        assertThat(subscription.matches(Map.of("resourceType", "project", "status", "DELETED")))
                .isFalse();
    }

    @Test
    void anUnknownFilterKeyIsRejected() {
        // A free-form key set invites an expensive predicate against an unindexed
        // column, evaluated on every event for every subscription. Note that
        // organizationId is deliberately not filterable at all.
        assertThatThrownBy(() -> subscription(Map.of("organizationId", "other-tenant")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not filterable");
    }

    @Test
    void anEmptyFilterValueIsRejected() {
        assertThatThrownBy(() -> subscription(Map.of("status", "")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> subscription(Map.of("status", "   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAllowlistIsTheRealBoundOnFilterCount() {
        // A Map collapses duplicate keys, so a test cannot build more than five
        // distinct filters from five permitted keys. The allowlist is the bound,
        // not a separate counter.
        Map<String, String> everyKey = Map.of(
                "projectId", "p", "environmentId", "e", "resourceType", "t",
                "resourceId", "r", "status", "ACTIVE");

        EventSubscription subscription = subscription(everyKey);

        assertThat(subscription.filterSet()).hasSize(5);
    }

    @Test
    void anOversizedFilterValueIsRejected() {
        assertThatThrownBy(() -> subscription(Map.of("resourceId", "x".repeat(201))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPinnedEnvironmentAcceptsOnlyItsOwn() {
        EventSubscription subscription = subscription(null);

        assertThat(subscription.matchesEnvironment(environmentId)).isTrue();
        assertThat(subscription.matchesEnvironment(UUID.randomUUID())).isFalse();
    }

    @Test
    void anEnvironmentIsRequiredForEverySubscription() {
        assertThatThrownBy(() -> EventSubscription.create(organizationId, projectId,
                null, "endpoint_1", projectCreated(), 1, "missing environment", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("environmentId");
    }

    @Test
    void aNullEnvironmentNeverMatches() {
        EventSubscription subscription = subscription(null);

        assertThat(subscription.matchesEnvironment(null)).isFalse();
    }

    @Test
    void eventTypeMatchingIsExact() {
        EventSubscription subscription = subscription(null);

        assertThat(subscription.matchesType("developer.project.created")).isTrue();
        assertThat(subscription.matchesType("developer.project.updated")).isFalse();
    }

    @Test
    void suspensionStopsDeliveryAndResumptionRestoresIt() {
        EventSubscription subscription = subscription(null);
        subscription.suspend();

        assertThat(subscription.delivers()).isFalse();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);

        subscription.resume();
        assertThat(subscription.delivers()).isTrue();
    }

    @Test
    void cancellationIsTerminal() {
        EventSubscription subscription = subscription(null);
        subscription.cancel();

        assertThat(subscription.delivers()).isFalse();
        assertThatThrownBy(subscription::resume).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(subscription::suspend).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void filtersRoundTripThroughStorage() {
        EventSubscription subscription = subscription(
                Map.of("resourceType", "project", "status", "ACTIVE"));

        assertThat(subscription.filterSet())
                .containsEntry("resourceType", "project")
                .containsEntry("status", "ACTIVE");
    }

    @Test
    void theTenantIsFixedAtCreationAndUnaffectedByFilters() {
        // A filter cannot change the tenant: the subscription was created for one
        // organization and there is no path that rewrites it.
        EventSubscription subscription = subscription(Map.of("resourceType", "project"));

        assertThat(subscription.getOrganizationId()).isEqualTo(organizationId);
        assertThat(subscription.getProjectId()).isEqualTo(projectId);
        assertThat(subscription.getEnvironmentId()).isEqualTo(environmentId);
    }
}
