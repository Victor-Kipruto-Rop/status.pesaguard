package com.pesaguard.backend.loadtest.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record LoadTestThreshold(
        @NotBlank String metric,
        @NotBlank String operator,
        @PositiveOrZero double value) {
}
