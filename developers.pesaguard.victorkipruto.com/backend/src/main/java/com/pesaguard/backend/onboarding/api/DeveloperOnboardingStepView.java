package com.pesaguard.backend.onboarding.api;

import java.time.Instant;

public record DeveloperOnboardingStepView(
        String key,
        String status,
        boolean required,
        boolean conditional,
        String blockedReason,
        Instant startedAt,
        Instant completedAt,
        Instant skippedAt) {
}
