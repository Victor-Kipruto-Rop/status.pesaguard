package com.pesaguard.backend.loadtest.api;

import java.util.Map;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record MetricIngestRequest(
        @NotEmpty @Size(max = 64) Map<String, Object> values) {
}
