package com.pesaguard.backend.loadtest.api;

import java.time.Instant;
import java.util.Map;

import com.pesaguard.backend.loadtest.domain.LoadTestMetric;

public record LoadTestMetricView(Instant observedAt, Map<String, Object> values) {
    public static LoadTestMetricView from(LoadTestMetric metric) {
        return new LoadTestMetricView(metric.getObservedAt(), metric.getValues());
    }
}
