package com.pesaguard.backend.sandbox.api;

import java.util.UUID;

import com.pesaguard.backend.sandbox.domain.SandboxLimits;

/** A sandbox's own quotas. */
public record SandboxLimitsView(
        UUID sandboxId,
        int requestsPerMinute,
        int burstRequests,
        int maxApiKeys,
        int maxCredentials,
        int maxWebhookEndpoints,
        int maxEventsPerMinute,
        int maxRequestBodyBytes,
        int maxResponseBodyBytes,
        int executionTimeoutMs,
        int maxHistoryEntries) {

    public static SandboxLimitsView from(SandboxLimits limits) {
        return new SandboxLimitsView(limits.getSandboxId(), limits.getRequestsPerMinute(),
                limits.getBurstRequests(), limits.getMaxApiKeys(), limits.getMaxCredentials(),
                limits.getMaxWebhookEndpoints(), limits.getMaxEventsPerMinute(),
                limits.getMaxRequestBodyBytes(), limits.getMaxResponseBodyBytes(),
                limits.getExecutionTimeoutMs(), limits.getMaxHistoryEntries());
    }
}