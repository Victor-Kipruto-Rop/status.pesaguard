package com.pesaguard.backend.credentials.api;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyHistoryView(
        UUID id,
        UUID apiKeyId,
        String action,
        String fromStatus,
        String toStatus,
        UUID actorUserId,
        String reason,
        Instant createdAt) {
}