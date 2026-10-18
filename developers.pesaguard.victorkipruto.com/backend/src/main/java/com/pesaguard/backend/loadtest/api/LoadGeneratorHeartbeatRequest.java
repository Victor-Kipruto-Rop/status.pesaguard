package com.pesaguard.backend.loadtest.api;

import com.pesaguard.backend.loadtest.domain.GeneratorStatus;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record LoadGeneratorHeartbeatRequest(
        @NotNull GeneratorStatus status,
        @Min(0) @Max(100000) int currentVus,
        @Min(0) @Max(100000) int currentRps,
        @Min(0) @Max(100) double cpuPercent,
        @Min(0) @Max(100) double memoryPercent) {
}
