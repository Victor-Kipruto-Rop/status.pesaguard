package com.pesaguard.backend.environment.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.environment.domain.EnvironmentHistory;

public interface EnvironmentHistoryRepository extends JpaRepository<EnvironmentHistory, UUID> {

    List<EnvironmentHistory> findByEnvironmentIdOrderByCreatedAtDesc(UUID environmentId);

    long countByEnvironmentId(UUID environmentId);
}