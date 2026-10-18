package com.pesaguard.backend.credentials.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record CreatedApiKeyView(
        UUID id,
        String name,
        String key,
        String prefix,
        Set<String> scopes,
        Instant expiresAt,
        String baseUrl) {
}
