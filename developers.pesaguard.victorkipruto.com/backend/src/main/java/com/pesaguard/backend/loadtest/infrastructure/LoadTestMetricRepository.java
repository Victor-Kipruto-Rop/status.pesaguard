package com.pesaguard.backend.loadtest.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.loadtest.domain.LoadTestMetric;

public interface LoadTestMetricRepository extends JpaRepository<LoadTestMetric, UUID> {
    List<LoadTestMetric> findByRunIdOrderByObservedAtAsc(UUID runId);
}
