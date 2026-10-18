package com.pesaguard.backend.scopes.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A parsed API scope, in the form {@code resource:action}.
 *
 * <p>Scopes are matched by exact value. No prefix matching, no wildcard, and no
 * case folding: {@code WEBHOOKS:READ} is not {@code webhooks:read}, it is a
 * different string that does not exist in the registry. This mirrors
 * {@link com.pesaguard.backend.oauth.domain.RedirectUriPolicy} — the parts of an
 * OAuth server that get attacked are exactly the parts that get fuzzy matching.
 *
 * <p>An instance is only constructible from a syntactically valid string, so any
 * {@code ApiScope} in the system is already well formed. Whether it <em>exists</em>
 * is a separate question answered by the registry.
 */
public record ApiScope(String resource, String action) implements Comparable<ApiScope> {

    /** Same grammar enforced by the api_scopes_name_format database constraint. */
    private static final Pattern PATTERN =
            Pattern.compile("^[a-z][a-z0-9_-]{1,47}:[a-z][a-z0-9_-]{1,23}$");

    public ApiScope {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(action, "action");
        if (!resource.equals(resource.toLowerCase(Locale.ROOT))
                || !action.equals(action.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Scope parts must be lowercase");
        }
    }

    public static boolean isValidFormat(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    /**
     * Parses without throwing, for validating untrusted client input.
     *
     * @return empty when the value is not a syntactically valid scope
     */
    public static java.util.Optional<ApiScope> tryParse(String value) {
        if (!isValidFormat(value)) {
            return java.util.Optional.empty();
        }
        int separator = value.indexOf(':');
        return java.util.Optional.of(new ApiScope(value.substring(0, separator), value.substring(separator + 1)));
    }

    /** Canonical value, e.g. {@code webhooks:read}. */
    public String value() {
        return resource + ":" + action;
    }

    /** A read scope does not mutate state. Write scopes do. */
    public boolean isWrite() {
        return "write".equals(action);
    }

    @Override
    public int compareTo(ApiScope other) {
        return value().compareTo(other.value());
    }

    @Override
    public String toString() {
        return value();
    }
}