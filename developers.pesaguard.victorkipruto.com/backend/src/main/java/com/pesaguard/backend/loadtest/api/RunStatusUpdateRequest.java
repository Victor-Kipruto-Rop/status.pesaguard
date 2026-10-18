package com.pesaguard.backend.loadtest.api;

import java.util.Map;

import com.pesaguard.backend.loadtest.domain.LoadTestLifecycle;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RunStatusUpdateRequest(
        @NotNull LoadTestLifecycle status,
        @Size(max = 500) String failureReason,
        Map<String, Object> resultSummary,
        @Size(max = 16) String verdict) {
}
