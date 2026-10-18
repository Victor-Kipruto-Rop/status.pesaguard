package com.pesaguard.backend.audit.api;

import java.time.Instant;
import java.util.UUID;

public record AuditEventView(
        UUID id,
        long sequenceNumber,
        UUID actorUserId,
        String action,
        String resourceType,
        String resourceId,
        UUID requestId,
        UUID correlationId,
        String ipAddress,
        String userAgent,
        short hashVersion,
        String metadata,
        String previousHash,
        String eventHash,
        Instant createdAt) {
}
