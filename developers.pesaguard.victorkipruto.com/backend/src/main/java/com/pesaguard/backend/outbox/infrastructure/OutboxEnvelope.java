package com.pesaguard.backend.outbox.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pesaguard.backend.outbox.domain.OutboxEvent;
import com.pesaguard.backend.tenancy.DeveloperTenantId;

/**
 * The wire form of an event published to the broker.
 *
 * <p>Every field a consumer needs is carried explicitly rather than relying on
 * transport headers. Headers are invisible to a consumer replaying from a file or
 * inspecting the topic by hand, and the envelope is what an integrator's
 * documentation describes, so it must be self-contained.
 *
 * <p>{@code event_id} and {@code event_type} lead deliberately: a consumer's very
 * first action is deduplicating on {@code event_id}, because delivery is
 * at-least-once and the same event can arrive more than once.
 */
public record OutboxEnvelope(
        String eventId,
        String eventType,
        int eventVersion,
        String occurredAt,
        String organizationId,
        String projectId,
        String environmentId,
        String tenantId,
        String correlationId,
        String traceId,
        String source,
        String payload) {

    public static OutboxEnvelope from(OutboxEvent event) {
        String organizationId = event.getOrganizationId() == null
                ? null : event.getOrganizationId().toString();
        String projectId = event.getProjectId() == null
                ? null : event.getProjectId().toString();
        String environmentId = event.getEnvironmentId() == null
                ? null : event.getEnvironmentId().toString();
        String tenantId = organizationId == null || projectId == null || environmentId == null
                ? null : DeveloperTenantId.derive(organizationId, projectId, environmentId);
        return new OutboxEnvelope(
                event.getEventId().toString(),
                event.getEventType(),
                event.getEventVersion(),
                event.getCreatedAt() == null ? null : event.getCreatedAt().toString(),
                organizationId,
                projectId,
                environmentId,
                tenantId,
                event.getCorrelationId(),
                event.getTraceId(),
                event.getSource(),
                event.getPayload());
    }

    /**
     * Renders the envelope.
     *
     * <p>Written by hand rather than with a JSON library: the shape is fixed and
     * adding Jackson for one record would introduce a dependency whose escaping
     * rules would then have to be trusted for a security-relevant payload.
     * {@code payload} is copied through verbatim because it was validated as JSON
     * when it was written to the outbox.
     */
    public String toJson() {
        Map<String, String> fields = new LinkedHashMap<>();
        putIfPresent(fields, "event_id", eventId);
        putIfPresent(fields, "event_type", eventType);
        putIfPresent(fields, "occurred_at", occurredAt);
        putIfPresent(fields, "organization_id", organizationId);
        putIfPresent(fields, "project_id", projectId);
        putIfPresent(fields, "environment_id", environmentId);
        putIfPresent(fields, "tenant_id", tenantId);
        putIfPresent(fields, "correlation_id", correlationId);
        putIfPresent(fields, "trace_id", traceId);
        putIfPresent(fields, "source", source);
        // Rendered with the string renderer: the event version is the only value
        // emitted as a JSON number, and mixing the two renderers per field made
        // it too easy for a numeric-looking correlation id to lose its quotes.
        StringBuilder json = new StringBuilder(160).append('{');
        json.append("\"event_version\":").append(eventVersion);
        for (Map.Entry<String, String> field : fields.entrySet()) {
            json.append(',').append('"').append(escape(field.getKey())).append("\":\"")
                    .append(escape(field.getValue())).append('"');
        }
        // Appended last and unescaped: the payload was validated as JSON when it
        // was written to the outbox, so escaping it here would double-encode it
        // and every consumer would receive a JSON string containing JSON.
        json.append(",\"payload\":").append(payload == null ? "{}" : payload);
        return json.append('}').toString();
    }

    private static void putIfPresent(Map<String, String> fields, String name, String value) {
        if (value != null) {
            fields.put(name, value);
        }
    }

    /**
     * Escapes a JSON string.
     *
     * <p>Control characters are escaped rather than dropped. A raw newline in a
     * value would break the envelope into two lines and corrupt the record for
     * every downstream consumer, which is why event names are also grammar-checked
     * in the database.
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}