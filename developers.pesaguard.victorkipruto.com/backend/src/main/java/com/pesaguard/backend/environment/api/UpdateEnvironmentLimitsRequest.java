package com.pesaguard.backend.environment.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateEnvironmentLimitsRequest(
        @Min(1) @Max(100000) int requestsPerMinute,
        @Min(1) @Max(10000) int burstRequests,
        @Min(1) @Max(100) int maxApiKeys,
        @Min(1) @Max(500) int maxCredentials,
        @Min(1) @Max(525600) int credentialRotationIntervalMinutes) {
}
