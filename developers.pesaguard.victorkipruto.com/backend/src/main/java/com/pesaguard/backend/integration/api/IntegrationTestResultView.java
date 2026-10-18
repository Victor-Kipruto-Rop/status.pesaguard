package com.pesaguard.backend.integration.api;

import java.util.UUID;

public record IntegrationTestResultView(
        String status,
        String integrationStatus,
        String healthStatus,
        UUID requestId,
        long latencyMs,
        String failureCategory,
        String message) {
}
