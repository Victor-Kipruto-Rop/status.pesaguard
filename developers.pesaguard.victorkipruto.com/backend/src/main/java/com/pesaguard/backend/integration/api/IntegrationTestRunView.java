package com.pesaguard.backend.integration.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.integration.domain.IntegrationTestRun;

public record IntegrationTestRunView(
        UUID id,
        String testType,
        String status,
        UUID requestId,
        Long latencyMs,
        String failureCategory,
        String message,
        Instant startedAt,
        Instant completedAt) {

    public static IntegrationTestRunView from(IntegrationTestRun run) {
        return new IntegrationTestRunView(run.getId(), run.getTestType(), run.getStatus().name(),
                run.getRequestId(), run.getLatencyMs(), run.getFailureCategory(),
                run.getSafeMessage(), run.getStartedAt(), run.getCompletedAt());
    }
}
