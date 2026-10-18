package com.pesaguard.backend.sandbox.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Sandbox quotas.
 *
 * <p>Separate from environment limits so that a noisy integration test exhausts
 * the sandbox budget rather than the environment's.
 */
public record UpdateSandboxLimitsRequest(
        @Min(1) @Max(100000) int requestsPerMinute,
        @Min(1) @Max(10000) int burstRequests,
        @Min(1) @Max(100) int maxApiKeys,
        @Min(1) @Max(500) int maxCredentials,
        @Min(1) @Max(100) int maxWebhookEndpoints,
        @Min(1) @Max(100000) int maxEventsPerMinute,
        @Min(1) @Max(1048576) int maxRequestBodyBytes,
        @Min(1) @Max(1048576) int maxResponseBodyBytes,
        // Ceiling mirrors the domain constant; the service clamps regardless.
        @Min(1) @Max(30000) int executionTimeoutMs,
        @Min(1) @Max(1000) int maxHistoryEntries) {
}