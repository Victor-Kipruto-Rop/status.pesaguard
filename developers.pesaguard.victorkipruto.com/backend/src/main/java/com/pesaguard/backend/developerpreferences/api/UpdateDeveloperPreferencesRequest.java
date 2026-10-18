package com.pesaguard.backend.developerpreferences.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateDeveloperPreferencesRequest(
        @Min(1000) @Max(60000) int requestTimeoutMs,
        @Min(0) @Max(5) int retryCount) {
}
