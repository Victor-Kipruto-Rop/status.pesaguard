package com.pesaguard.backend.events.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Lifecycle, versioning and deprecation.
 *
 * <p>The behaviour worth protecting is the sunset gate: an event must not be
 * retired before the date its subscribers were told, and must stop being emitted
 * at sunset even if the retirement sweep is late. Both prevent silently breaking
 * live integrations.
 */
class EventTypeDefinitionTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final String SCHEMA = "{\"type\":\"object\"}";

    private EventTypeDefinition definition(String name) {
        return new EventTypeDefinition(name, "Something happened.", EventCategory.PLATFORM,
                1, SCHEMA, EventLifecycle.ACTIVE);
    }

    @Test
    void anActiveEventIsEmittable() {
        assertThat(definition("developer.project.created").shouldEmit(NOW)).isTrue();
    }

    @Test
    void aDraftEventIsNotEmitted() {
        EventTypeDefinition draft = new EventTypeDefinition("developer.project.created",
                "Not ready.", EventCategory.PLATFORM, 1, SCHEMA, EventLifecycle.DRAFT);

        assertThat(draft.shouldEmit(NOW)).isFalse();
    }

    @Test
    void aDraftCanBeActivated() {
        EventTypeDefinition draft = new EventTypeDefinition("developer.project.created",
                "Not ready.", EventCategory.PLATFORM, 1, SCHEMA, EventLifecycle.DRAFT);
        draft.activate();

        assertThat(draft.getLifecycle()).isEqualTo(EventLifecycle.ACTIVE);
        assertThat(draft.shouldEmit(NOW)).isTrue();
    }

    @Test
    void onlyADraftCanBeActivated() {
        EventTypeDefinition active = definition("developer.project.created");

        assertThatThrownBy(active::activate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDeprecatedEventIsStillEmittedBeforeItsSunset() {
        EventTypeDefinition definition = definition("developer.project.created");
        Instant sunset = NOW.plusSeconds(86400);
        definition.deprecate("developer.project.updated", sunset, "renamed");

        // Stopping the event here would break every live subscriber. An outage is
        // not a deprecation strategy.
        assertThat(definition.shouldEmit(NOW)).isTrue();
        assertThat(definition.getLifecycle()).isEqualTo(EventLifecycle.DEPRECATED);
        assertThat(definition.getReplacedBy()).isEqualTo("developer.project.updated");
        assertThat(definition.getSunsetAt()).isEqualTo(sunset);
    }

    @Test
    void aDeprecatedEventStopsBeingEmittedAfterSunset() {
        EventTypeDefinition definition = definition("developer.project.created");
        definition.deprecate("developer.project.updated", NOW.plusSeconds(86400), "renamed");

        // Emission stops on the date even if the retirement sweep has not run, so a
        // late sweep cannot keep an event alive past what subscribers were promised.
        assertThat(definition.shouldEmit(NOW.plusSeconds(86401))).isFalse();
    }

    @Test
    void retirementBeforeSunsetIsRefused() {
        EventTypeDefinition definition = definition("developer.project.created");
        definition.deprecate("developer.project.updated", NOW.plusSeconds(86400), "renamed");

        // This is the rule that stops an operator breaking integrations by retiring
        // early, however sure they are that nobody subscribes.
        assertThatThrownBy(() -> definition.retire(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sunset");
    }

    @Test
    void retirementAfterSunsetIsAllowed() {
        EventTypeDefinition definition = definition("developer.project.created");
        definition.deprecate("developer.project.updated", NOW.plusSeconds(86400), "renamed");

        definition.retire(NOW.plusSeconds(86401));

        assertThat(definition.getLifecycle()).isEqualTo(EventLifecycle.RETIRED);
        assertThat(definition.shouldEmit(NOW.plusSeconds(90000))).isFalse();
    }

    @Test
    void retirementIsIdempotent() {
        EventTypeDefinition definition = definition("developer.project.created");
        definition.deprecate("developer.project.updated", NOW, "renamed");
        definition.retire(NOW.plusSeconds(1));
        definition.retire(NOW.plusSeconds(2));

        assertThat(definition.getLifecycle()).isEqualTo(EventLifecycle.RETIRED);
    }

    @Test
    void onlyADeprecatedEventCanBeRetired() {
        EventTypeDefinition active = definition("developer.project.created");

        assertThatThrownBy(() -> active.retire(NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deprecationRequiresASunsetDate() {
        EventTypeDefinition definition = definition("developer.project.created");

        // A deprecation with no end leaves subscribers guessing whether the event
        // is still coming.
        assertThatThrownBy(() -> definition.deprecate("developer.project.updated", null, "renamed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sunset");
    }

    @Test
    void anEventCannotReplaceItself() {
        EventTypeDefinition definition = definition("developer.project.created");

        assertThatThrownBy(() -> definition.deprecate("developer.project.created", NOW, "nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRetiredEventCannotBeDeprecatedAgain() {
        EventTypeDefinition definition = definition("developer.project.created");
        definition.deprecate("developer.project.updated", NOW, "renamed");
        definition.retire(NOW.plusSeconds(1));

        assertThatThrownBy(() -> definition.deprecate("developer.project.updated", NOW, "again"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anInvalidNameIsRejectedAtRegistration() {
        assertThatThrownBy(() -> new EventTypeDefinition("not-a-valid-name", "d",
                EventCategory.PLATFORM, 1, SCHEMA, EventLifecycle.DRAFT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void versionMustBeAtLeastOne() {
        assertThatThrownBy(() -> new EventTypeDefinition("developer.project.created", "d",
                EventCategory.PLATFORM, 0, SCHEMA, EventLifecycle.DRAFT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nameSegmentsAreExposedForFiltering() {
        EventTypeDefinition definition = definition("developer.api_key.revoked");

        assertThat(definition.getNamespace()).isEqualTo("developer");
        assertThat(definition.getEntity()).isEqualTo("api_key");
        assertThat(definition.getAction()).isEqualTo("revoked");
        assertThat(definition.eventType().value()).isEqualTo("developer.api_key.revoked");
    }

    @Test
    void lifecycleEmittabilityIsEnumeratedConsistently() {
        assertThat(EventLifecycle.ACTIVE.isEmittable()).isTrue();
        assertThat(EventLifecycle.DEPRECATED.isEmittable()).isTrue();
        assertThat(EventLifecycle.DRAFT.isEmittable()).isFalse();
        assertThat(EventLifecycle.RETIRED.isEmittable()).isFalse();
        assertThat(EventLifecycle.RETIRED.isTerminal()).isTrue();
    }
}
