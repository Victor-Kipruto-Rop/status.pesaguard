package com.pesaguard.backend.security.authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A live session, for {@code GET /api/v1/auth/sessions}. */
public record SessionSummaryResponse(
        UUID id,
        String deviceLabel,
        String lastIp,
        Instant lastSeenAt,
        Instant expiresAt,
        /** True for the caller's own current session, so the UI can mark it. */
        boolean current) {
}