package com.pesaguard.backend.tenancy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record TenantEvent(
        UUID tenantId,
        UUID organizationId,
        UUID actorId,
        String eventType,
        UUID resourceId,
        UUID requestId,
        java.time.Instant occurredAt,
        Map<String, ?> metadata) {

    public TenantEvent {
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}