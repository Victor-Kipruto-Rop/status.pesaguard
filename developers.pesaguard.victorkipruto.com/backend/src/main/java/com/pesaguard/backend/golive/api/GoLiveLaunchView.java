package com.pesaguard.backend.golive.api;

import java.time.Instant;
import java.util.UUID;

public record GoLiveLaunchView(
        UUID id,
        UUID projectId,
        UUID environmentId,
        UUID verificationId,
        String status,
        String failureReason,
        Instant startedAt,
        Instant completedAt,
        String requestId) {
}
