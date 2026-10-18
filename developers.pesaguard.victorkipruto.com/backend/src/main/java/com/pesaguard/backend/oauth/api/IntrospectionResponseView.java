package com.pesaguard.backend.oauth.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Introspection result. When a token is inactive the identifying fields are
 * omitted, matching RFC 7662 so callers cannot probe token ownership.
 */
public record IntrospectionResponseView(
        boolean active,
        UUID applicationId,
        UUID userId,
        Set<String> scopes,
        Instant expiresAt,
        Instant issuedAt) {

    public static IntrospectionResponseView inactive() {
        return new IntrospectionResponseView(false, null, null, Set.of(), null, null);
    }

    public IntrospectionResponseView {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}