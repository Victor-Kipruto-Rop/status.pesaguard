package com.pesaguard.backend.golive.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GoLiveReadinessView(
        UUID verificationId,
        UUID projectId,
        String projectName,
        String projectStatus,
        UUID environmentId,
        String environmentName,
        String environmentStatus,
        String baseUrl,
        String state,
        int readinessPercent,
        int passedChecks,
        int failedChecks,
        int blockingChecks,
        int warningChecks,
        boolean canLaunch,
        Instant verifiedAt,
        List<GoLiveCheckView> checks) {
}
