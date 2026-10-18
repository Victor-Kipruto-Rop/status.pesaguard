package com.pesaguard.backend.loadtest.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.loadtest.domain.LoadTestResult;

public interface LoadTestResultRepository extends JpaRepository<LoadTestResult, UUID> {
    Optional<LoadTestResult> findByRunId(UUID runId);
}
