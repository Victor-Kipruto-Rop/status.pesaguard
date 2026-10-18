package com.pesaguard.backend.integration.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.integration.domain.IntegrationEvent;

public record IntegrationEventView(
        UUID id,
        String eventType,
        UUID requestId,
        String details,
        Instant createdAt) {

    public static IntegrationEventView from(IntegrationEvent event) {
        return new IntegrationEventView(event.getId(), event.getEventType(),
                event.getRequestId(), event.getDetails(), event.getCreatedAt());
    }
}
