package com.pesaguard.backend.loadtest.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.loadtest.domain.LoadTestLifecycle;
import com.pesaguard.backend.loadtest.domain.LoadTestRun;

import jakarta.persistence.LockModeType;

public interface LoadTestRunRepository extends JpaRepository<LoadTestRun, UUID> {
    List<LoadTestRun> findByLoadTestIdAndOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
            UUID loadTestId, UUID organizationId, UUID projectId, UUID environmentId);

    Optional<LoadTestRun> findByIdAndLoadTestIdAndOrganizationIdAndProjectIdAndEnvironmentId(
            UUID id, UUID loadTestId, UUID organizationId, UUID projectId, UUID environmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from LoadTestRun run where run.id = :id")
    Optional<LoadTestRun> findByIdForUpdate(@Param("id") UUID id);

    long countByOrganizationIdAndStatusIn(UUID organizationId, List<LoadTestLifecycle> statuses);
}
