package com.pesaguard.backend.loadtest.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "load_test_metrics")
public class LoadTestMetric {
    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "generator_id", nullable = false)
    private UUID generatorId;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> values;

    protected LoadTestMetric() {
    }

    public static LoadTestMetric record(UUID runId, UUID generatorId, Instant observedAt,
            Map<String, Object> values) {
        LoadTestMetric metric = new LoadTestMetric();
        metric.id = UUID.randomUUID();
        metric.runId = runId;
        metric.generatorId = generatorId;
        metric.observedAt = observedAt;
        metric.values = Map.copyOf(values);
        return metric;
    }

    public Instant getObservedAt() { return observedAt; }
    public Map<String, Object> getValues() { return values; }
}
