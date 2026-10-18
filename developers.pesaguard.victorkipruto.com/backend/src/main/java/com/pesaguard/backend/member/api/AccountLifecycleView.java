package com.pesaguard.backend.member.api;

import java.time.Instant;

import com.pesaguard.backend.member.domain.UserStatus;

public record AccountLifecycleView(
        UserStatus status,
        Instant deletionRequestedAt,
        Instant deletionCompletesAt) {
}
