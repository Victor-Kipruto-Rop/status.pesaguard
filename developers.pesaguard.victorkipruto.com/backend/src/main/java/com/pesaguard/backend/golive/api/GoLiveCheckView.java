package com.pesaguard.backend.golive.api;

import java.time.Instant;

public record GoLiveCheckView(
        String checkId,
        String name,
        String category,
        String severity,
        String status,
        boolean blocking,
        String description,
        String result,
        String remediation,
        String remediationRoute,
        Instant checkedAt) {
}
