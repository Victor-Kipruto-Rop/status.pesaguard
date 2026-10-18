package com.pesaguard.backend.integration.api;

import java.time.Instant;
import java.util.UUID;

public record IntegrationHealthView(
        UUID integrationId,
        String integrationStatus,
        String healthStatus,
        Instant lastTestedAt,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        UUID lastRequestId) {
}
