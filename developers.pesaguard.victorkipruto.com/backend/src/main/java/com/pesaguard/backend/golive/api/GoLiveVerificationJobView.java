package com.pesaguard.backend.golive.api;

import java.time.Instant;
import java.util.UUID;

public record GoLiveVerificationJobView(
        UUID id,
        String status,
        UUID verificationId,
        String failureReason,
        Instant queuedAt,
        Instant startedAt,
        Instant completedAt,
        GoLiveReadinessView result) {
}
