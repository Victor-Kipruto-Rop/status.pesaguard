package com.pesaguard.backend.outbox.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.outbox.domain.OutboxEvent;
import com.pesaguard.backend.outbox.domain.OutboxStatus;
import com.pesaguard.backend.tenancy.DeveloperTenantId;

/**
 * The wire form published to the broker.
 *
 * <p>Hand-written JSON is a risk worth testing directly: an unescaped quote or
 * newline in any field would corrupt the record for every consumer on the topic,
 * and the corruption would appear downstream rather than at the publisher.
 */
class OutboxEnvelopeTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private OutboxEvent event(String eventType, String correlationId) {
        return OutboxEvent.record(UUID.randomUUID(), eventType, 1,
                UUID.randomUUID(), UUID.randomUUID(), "partition-1",
                correlationId, "trace-1", "{\"id\":\"p1\"}", "developer-platform", T0);
    }

    @Test
    void everyFieldAConsumerNeedsIsPresent() {
        OutboxEvent source = event("developer.project.created", "corr-1");

        String json = OutboxEnvelope.from(source).toJson();

        assertThat(json)
                .contains("\"event_id\":\"" + source.getEventId() + "\"")
                .contains("\"event_type\":\"developer.project.created\"")
                .contains("\"event_version\":1")
                .contains("\"organization_id\"")
                .contains("\"correlation_id\":\"corr-1\"")
                .contains("\"trace_id\":\"trace-1\"")
                .contains("\"source\":\"developer-platform\"")
                .doesNotContain("\"tenant_id\"");
    }

    @Test
    void environmentScopedEventsIncludeTheirDerivedTenantIdentity() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID environmentId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        OutboxEvent source = OutboxEvent.record(UUID.randomUUID(), "developer.api_key.revoked", 1,
                organizationId, projectId, environmentId, organizationId.toString(),
                "corr-1", "trace-1", "{\"id\":\"key-1\"}", "developer-platform", T0);

        String json = OutboxEnvelope.from(source).toJson();

        assertThat(json)
                .contains("\"environment_id\":\"" + environmentId + "\"")
                .contains("\"tenant_id\":\""
                        + DeveloperTenantId.derive(organizationId.toString(),
                                projectId.toString(), environmentId.toString())
                        + "\"")
                .contains("\"tenant_id\":\"dp_2934bbfdc6fee932108c14a00ca22a9e8b6217d596b1d0988edefd178e1cb1b7\"");
    }

    @Test
    void theVersionIsEmittedAsANumberNotAString() {
        // A quoted version would make every consumer parse it as text and
        // silently mis-order two schema versions of the same event.
        assertThat(OutboxEnvelope.from(event("developer.project.created", "c")).toJson())
                .contains("\"event_version\":1,")
                .doesNotContain("\"event_version\":\"1\"");
    }

    @Test
    void thePayloadIsCopiedThroughVerbatim() {
        OutboxEvent source = event("developer.project.created", "c");

        assertThat(OutboxEnvelope.from(source).toJson())
                .contains("\"payload\":{\"id\":\"p1\"}");
    }

    @Test
    void anAbsentCorrelationIdIsOmittedRatherThanEmittedAsNull() {
        // A null in the envelope forces every consumer to null-check before it
        // can use the field.
        assertThat(OutboxEnvelope.from(event("developer.project.created", null)).toJson())
                .doesNotContain("correlation_id")
                .doesNotContain("null");
    }

    @Test
    void anAbsentTenantIsOmittedNotNulled() {
        OutboxEvent platformWide = OutboxEvent.record(UUID.randomUUID(),
                "developer.catalog.published", 1, null, null,
                "platform", null, null, "{}", "developer-platform", T0);

        assertThat(OutboxEnvelope.from(platformWide).toJson())
                .doesNotContain("organization_id")
                .doesNotContain("null");
    }

    @Test
    void aQuoteInAFieldIsEscaped() {
        // Unescaped, this would terminate the JSON string early and merge two
        // fields into garbage for every consumer.
        String json = OutboxEnvelope.from(
                event("developer.project.created", "corr\"injected\":true")).toJson();

        assertThat(json).contains("corr\\\"injected\\\":true");
        // The injected key must not appear as a real top-level field.
        assertThat(json).doesNotContain("\"injected\":true,");
    }

    @Test
    void aBackslashIsEscaped() {
        String json = OutboxEnvelope.from(
                event("developer.project.created", "path\\to\\thing")).toJson();

        assertThat(json).contains("path\\\\to\\\\thing");
    }

    @Test
    void aNewlineIsEscapedRatherThanEmittedRaw() {
        // A raw newline would split the record across lines and break any
        // consumer reading it line by line.
        String json = OutboxEnvelope.from(
                event("developer.project.created", "line1\nline2")).toJson();

        assertThat(json).contains("line1\\nline2");
        assertThat(json).doesNotContain("line1\nline2");
    }

    @Test
    void aControlCharacterIsEscapedAsUnicode() {
        String json = OutboxEnvelope.from(
                event("developer.project.created", "before\u0001after")).toJson();

        assertThat(json).contains("before\\u0001after");
    }

    @Test
    void aValueThatLooksNumericIsStillQuoted() {
        // Only the version is emitted unquoted. A correlation id of "12345" is a
        // string, and emitting it bare would make it an int in every consumer.
        String json = OutboxEnvelope.from(
                event("developer.project.created", "12345")).toJson();

        assertThat(json).contains("\"correlation_id\":\"12345\"");
    }

    @Test
    void theEnvelopeReflectsTheEventIdentityThatSurvivesRetries() {
        OutboxEvent source = event("developer.project.created", "c");
        UUID identity = source.getEventId();

        source.markPublished(T0.plusSeconds(5));
        String json = OutboxEnvelope.from(source).toJson();

        assertThat(json).contains(identity.toString());
    }
}
