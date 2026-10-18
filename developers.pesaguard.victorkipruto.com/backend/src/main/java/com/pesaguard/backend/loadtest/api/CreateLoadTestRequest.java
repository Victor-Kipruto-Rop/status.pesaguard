package com.pesaguard.backend.loadtest.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pesaguard.backend.loadtest.domain.LoadPattern;
import com.pesaguard.backend.loadtest.domain.LoadTestStage;
import com.pesaguard.backend.loadtest.domain.LoadTestThreshold;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLoadTestRequest(
        @NotNull UUID projectId,
        @NotNull UUID environmentId,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 1024) String endpointPath,
        @NotBlank @Pattern(regexp = "GET|HEAD|OPTIONS|POST|PUT|PATCH") String httpMethod,
        @NotBlank @Size(max = 100) String contentType,
        @Size(max = 32) Map<@NotBlank @Size(max = 80) String, @NotBlank @Size(max = 2048) String> headers,
        @Size(max = 65536) String requestBody,
        UUID apiKeyId,
        @NotNull LoadPattern loadPattern,
        @Min(1) @Max(100000) int targetVus,
        @Min(1) @Max(100000) Integer targetRps,
        @Min(1) @Max(100000) Integer maximumRps,
        @Min(1) @Max(86400) int maximumDurationSeconds,
        @NotNull @Size(min = 1, max = 100) List<@Valid LoadTestStage> stages,
        @NotNull @Size(max = 32) List<@Valid LoadTestThreshold> thresholds) {
}
