package com.pesaguard.backend.loadtest.domain;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record LoadTestStage(
        @Min(1) @Max(86400) int durationSeconds,
        @Min(0) @Max(100000) int targetVus) {
}
