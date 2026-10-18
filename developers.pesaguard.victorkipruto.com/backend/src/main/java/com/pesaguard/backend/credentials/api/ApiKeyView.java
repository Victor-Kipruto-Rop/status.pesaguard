package com.pesaguard.backend.credentials.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ApiKeyView(
        UUID id,
        UUID projectId,
        UUID environmentId,
        String name,
        String prefix,
        Set<String> scopes,
        String status,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt,
        long requestCount,
        UUID rotatedFromId,
        Set<String> ipAllowlist,
        String lastUsedIp,
        String lastUsedCountry,
        String lastUsedDevice,
        Instant createdAt) {
}
