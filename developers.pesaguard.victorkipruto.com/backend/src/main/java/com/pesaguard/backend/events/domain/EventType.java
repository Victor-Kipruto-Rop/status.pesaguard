package com.pesaguard.backend.events.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A namespaced event name: {@code namespace.entity.action}.
 *
 * <p>For example {@code developer.project.created} — "in the developer
 * namespace, the project entity, the created action".
 *
 * <p>The grammar is deliberately strict. Event names end up in delivery headers,
 * log lines, metric labels and developer-facing dashboards, where a name
 * containing a newline, a space or a quote is a log-injection and metric-poisoning
 * hazard. A name that cannot be expressed here never reaches any of those.
 *
 * <p>Each segment is lowercase with hyphens and digits. The reserved
 * {@code *} wildcard is deliberately <em>not</em> accepted here: a wildcard is a
 * subscription filter concept, not an event name, and allowing it in a name would
 * make two distinct things share a namespace.
 */
public record EventType(String namespace, String entity, String action) implements Comparable<EventType> {

    /**
     * Same grammar as the V11 check constraint.
     *
     * <p>Three or four dot-separated segments. Three covers
     * {@code developer.project.created}; four covers a nested entity path such as
     * {@code developer.webhook.delivery.failed}. The namespace is the first segment
     * and the action is always the last; everything between is the entity path.
     *
     * <p>Underscores are permitted inside a segment because the registered catalog
     * uses them ({@code developer.api_key.created}). Excluding them would have
     * rejected the platform's own event names.
     */
    private static final String SEGMENT = "[a-z][a-z0-9_-]{1,31}";
    private static final Pattern PATTERN = Pattern.compile(
            "^" + SEGMENT + "\\." + SEGMENT + "\\." + SEGMENT
                    + "(\\.([a-z][a-z0-9_-]{1,31}))?$");

    public EventType {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(action, "action");
    }

    public static boolean isValidName(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    /**
     * Parses without throwing, for validating untrusted input.
     *
     * <p>{@code developer.webhook.delivery.failed} parses as namespace
     * {@code developer}, entity {@code webhook.delivery}, action {@code failed}.
     */
    public static java.util.Optional<EventType> tryParse(String value) {
        if (!isValidName(value)) {
            return java.util.Optional.empty();
        }
        String[] parts = value.split("\\.");
        String action = parts[parts.length - 1];
        String entity = String.join(".", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1));
        return java.util.Optional.of(new EventType(parts[0], entity, action));
    }

    /** Canonical dotted form, e.g. {@code developer.project.created}. */
    public String value() {
        return namespace + "." + entity + "." + action;
    }

    /** True for names in the {@code developer} namespace. */
    public boolean isDeveloperScoped() {
        return "developer".equals(namespace);
    }

    /**
     * Whether this name sits under a namespace.
     *
     * <p>Used to let a subscriber take every event from a namespace without
     * enumerating them. Matching is by exact namespace equality, never by prefix:
     * {@code developerx} must not match the {@code developer} namespace.
     */
    public boolean inNamespace(String candidate) {
        return namespace.equals(candidate == null ? null : candidate.toLowerCase(Locale.ROOT));
    }

    @Override
    public int compareTo(EventType other) {
        return value().compareTo(other.value());
    }

    @Override
    public String toString() {
        return value();
    }
}