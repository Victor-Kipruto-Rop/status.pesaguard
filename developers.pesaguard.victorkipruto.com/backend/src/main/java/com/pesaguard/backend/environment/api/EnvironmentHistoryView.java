package com.pesaguard.backend.environment.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.environment.domain.EnvironmentStatus;
import com.pesaguard.backend.environment.domain.EnvironmentType;

public record EnvironmentHistoryView(
        UUID id,
        UUID environmentId,
        String action,
        EnvironmentType fromType,
        EnvironmentType toType,
        EnvironmentStatus fromStatus,
        EnvironmentStatus toStatus,
        UUID actorUserId,
        String reason,
        Instant createdAt) {
}