package com.pesaguard.backend.loadtest.api;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterLoadGeneratorRequest(
        @NotNull UUID generatorId,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 80) String region,
        @Min(1) @Max(100000) int maxVus,
        @Min(0) @Max(100000) int maxRps,
        @NotBlank @Size(max = 40) String version,
        @NotBlank @Pattern(regexp = "K6") String executorType) {
}
