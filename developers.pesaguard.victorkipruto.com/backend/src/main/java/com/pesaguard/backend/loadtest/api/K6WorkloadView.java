package com.pesaguard.backend.loadtest.api;

import java.util.List;
import java.util.UUID;

public record K6WorkloadView(
        UUID runId,
        UUID loadTestId,
        int allocatedVus,
        int allocatedRps,
        String script,
        UUID apiKeyId,
        List<String> requiredSecretEnvironmentVariables) {
}
