package com.pesaguard.backend.loadtest.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.pesaguard.backend.loadtest.domain.LoadTestLifecycle;
import com.pesaguard.backend.loadtest.domain.LoadTestRun;

public record LoadTestRunView(
        UUID runId,
        UUID loadTestId,
        LoadTestLifecycle status,
        String availabilityReason,
        int configuredVus,
        int allocatedVus,
        Integer requestedRps,
        Long estimatedDurationSeconds,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> resultSummary,
        String failureReason,
        Instant createdAt) {
    public static LoadTestRunView from(LoadTestRun run) {
        return new LoadTestRunView(run.getId(), run.getLoadTestId(), run.getStatus(),
                run.getAvailabilityReason(), run.getRequestedVus(), run.getAllocatedVus(),
                run.getRequestedRps(), run.getEstimatedDurationSeconds(), run.getStartedAt(),
                run.getFinishedAt(), run.getResultSummary(), run.getFailureReason(), run.getCreatedAt());
    }
}
