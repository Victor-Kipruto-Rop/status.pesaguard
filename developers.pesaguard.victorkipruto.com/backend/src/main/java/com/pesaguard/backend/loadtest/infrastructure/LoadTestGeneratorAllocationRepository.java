package com.pesaguard.backend.loadtest.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.loadtest.domain.LoadTestGeneratorAllocation;

import jakarta.persistence.LockModeType;

public interface LoadTestGeneratorAllocationRepository
        extends JpaRepository<LoadTestGeneratorAllocation, UUID> {
    List<LoadTestGeneratorAllocation> findByRunIdOrderByGeneratorId(UUID runId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LoadTestGeneratorAllocation a where a.generatorId = :generatorId "
            + "and a.status = 'ALLOCATED' order by a.runId")
    List<LoadTestGeneratorAllocation> findAvailableForGenerator(@Param("generatorId") UUID generatorId);

    Optional<LoadTestGeneratorAllocation> findByRunIdAndGeneratorId(UUID runId, UUID generatorId);

    @Query("select coalesce(sum(a.allocatedVus), 0) from LoadTestGeneratorAllocation a "
            + "where a.generatorId = :generatorId and a.status = 'ALLOCATED'")
    long sumUnclaimedVus(@Param("generatorId") UUID generatorId);

    @Query("select coalesce(sum(a.allocatedRps), 0) from LoadTestGeneratorAllocation a "
            + "where a.generatorId = :generatorId and a.status = 'ALLOCATED'")
    long sumUnclaimedRps(@Param("generatorId") UUID generatorId);
}
