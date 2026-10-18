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
@Table(name = "load_test_results")
public class LoadTestResult {
    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false, unique = true)
    private UUID runId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> summary;

    @Column(nullable = false, length = 16)
    private String verdict;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected LoadTestResult() {
    }

    public static LoadTestResult record(UUID runId, Map<String, Object> summary, String verdict, Instant at) {
        LoadTestResult result = new LoadTestResult();
        result.id = UUID.randomUUID();
        result.runId = runId;
        result.summary = Map.copyOf(summary);
        result.verdict = verdict;
        result.recordedAt = at;
        return result;
    }

    public Map<String, Object> getSummary() { return summary; }
    public String getVerdict() { return verdict; }
    public Instant getRecordedAt() { return recordedAt; }
}
