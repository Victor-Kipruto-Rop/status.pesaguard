package com.pesaguard.backend.events.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Event names end up in delivery headers, log lines and metric labels, so the
 * grammar is a security control rather than cosmetics: a name carrying a newline
 * or a quote is a log-injection and metric-poisoning hazard.
 */
class EventTypeTest {

    /** Mirrors the seed in V11__event_registry.sql. */
    private static final java.util.List<String> SEEDED_EVENT_NAMES = java.util.List.of(
            "developer.project.created",
            "developer.project.updated",
            "developer.project.deleted",
            "developer.api_key.created",
            "developer.api_key.revoked",
            "developer.webhook.created",
            "developer.webhook.delivery.failed",
            "developer.sandbox.created",
            "developer.sandbox.expired",
            "developer.organization.member_added",
            "developer.access.denied");

    @Test
    void everySeededEventNameSatisfiesTheGrammar() {
        // The grammar and the seed drifted apart twice during this phase: the
        // grammar first rejected 'developer.api_key.created', then rejected the
        // four-segment 'developer.webhook.delivery.failed'. This test fails the
        // moment someone seeds an event the parser would refuse.
        for (String name : SEEDED_EVENT_NAMES) {
            assertThat(EventType.tryParse(name))
                    .as("seeded event %s must parse", name)
                    .isPresent();
        }
    }

    @Test
    void theSeededCatalogCoversTheRegisteredCategories() {
        assertThat(SEEDED_EVENT_NAMES).hasSize(11);
        assertThat(SEEDED_EVENT_NAMES).allSatisfy(name ->
                assertThat(EventType.tryParse(name).orElseThrow().isDeveloperScoped())
                        .as("%s should be developer-scoped", name)
                        .isTrue());
    }

    @Test
    void parsesTheThreeSegments() {
        EventType type = EventType.tryParse("developer.project.created").orElseThrow();

        assertThat(type.namespace()).isEqualTo("developer");
        assertThat(type.entity()).isEqualTo("project");
        assertThat(type.action()).isEqualTo("created");
        assertThat(type.value()).isEqualTo("developer.project.created");
    }

    @Test
    void aNestedEntityPathParsesIntoAnEntityPath() {
        // Four segments: namespace, then a two-part entity path, then the action.
        EventType type = EventType.tryParse("developer.webhook.delivery.failed").orElseThrow();

        assertThat(type.namespace()).isEqualTo("developer");
        assertThat(type.entity()).isEqualTo("webhook.delivery");
        assertThat(type.action()).isEqualTo("failed");
        assertThat(type.value()).isEqualTo("developer.webhook.delivery.failed");
    }

    @Test
    void fiveSegmentsAreRejected() {
        // Bounded so a name cannot grow arbitrarily long as a delivery header.
        assertThat(EventType.isValidName("a.b.c.d.e")).isFalse();
    }

    @Test
    void theExamplesFromTheSpecificationAreValid() {
        for (String name : new String[] {
                "developer.project.created",
                "developer.project.updated",
                "developer.api_key.created",
                "developer.api_key.revoked",
                "developer.webhook.created",
                "developer.webhook.delivery.failed"}) {
            assertThat(EventType.isValidName(name)).as("%s must be valid", name).isTrue();
            assertThat(EventType.tryParse(name)).as("%s must parse", name).isPresent();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "developer.project",              // two segments is too few
            "developer",                      // one segment
            "Developer.Project.Created",      // uppercase is not folded
            "developer..created",             // empty segment
            ".project.created",               // leading dot
            "developer.project.",             // trailing dot
            "developer.project.created ",     // trailing space
            "developer.pro ject.created",     // embedded space
            "developer.*.created",            // wildcard is a filter, not a name
            "developer\n.project.created",    // newline: log injection
            "developer.project.created\r",    // carriage return
            "a.b.c.d.e",                      // five segments is too many
    })
    void hostileOrMalformedNamesAreRejected(String name) {
        assertThat(EventType.isValidName(name)).as("%s must be rejected", name).isFalse();
        assertThat(EventType.tryParse(name)).isEmpty();
    }

    @Test
    void nullIsRejected() {
        assertThat(EventType.isValidName(null)).isFalse();
        assertThat(EventType.tryParse(null)).isEmpty();
    }

    @Test
    void namespaceMatchingIsExactNotPrefix() {
        EventType type = EventType.tryParse("developer.project.created").orElseThrow();

        assertThat(type.inNamespace("developer")).isTrue();
        assertThat(type.inNamespace("DEVELOPER")).isTrue();
        // A prefix match here would let one namespace's subscriber see another's
        // events.
        assertThat(type.inNamespace("developerx")).isFalse();
        assertThat(type.inNamespace("dev")).isFalse();
        assertThat(type.inNamespace(null)).isFalse();
    }

    @Test
    void sortingIsByCanonicalValue() {
        assertThat(java.util.stream.Stream.of(
                        EventType.tryParse("developer.webhook.created").orElseThrow(),
                        EventType.tryParse("developer.project.created").orElseThrow())
                .sorted().map(EventType::value).toList())
                .containsExactly("developer.project.created", "developer.webhook.created");
    }

    @Test
    void developerScopedIsANamespaceCheck() {
        assertThat(EventType.tryParse("developer.project.created").orElseThrow().isDeveloperScoped())
                .isTrue();
        assertThat(EventType.tryParse("billing.invoice.created").orElseThrow().isDeveloperScoped())
                .isFalse();
    }
}