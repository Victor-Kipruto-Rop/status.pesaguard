package com.pesaguard.backend.environment.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.environment.domain.EnvironmentLimits;

public record EnvironmentLimitsView(
        UUID environmentId,
        int requestsPerMinute,
        int burstRequests,
        int maxApiKeys,
        int maxCredentials,
        int credentialRotationIntervalMinutes,
        UUID updatedBy,
        Instant updatedAt) {

    public static EnvironmentLimitsView from(EnvironmentLimits limits) {
        return new EnvironmentLimitsView(limits.getEnvironmentId(), limits.getRequestsPerMinute(),
                limits.getBurstRequests(), limits.getMaxApiKeys(), limits.getMaxCredentials(),
                limits.getCredentialRotationIntervalMinutes(), limits.getUpdatedBy(), limits.getUpdatedAt());
    }
}
