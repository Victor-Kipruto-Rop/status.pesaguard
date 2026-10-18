package com.pesaguard.backend.loadtest.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.loadtest.domain.GeneratorStatus;
import com.pesaguard.backend.loadtest.domain.LoadGenerator;

import jakarta.persistence.LockModeType;

public interface LoadGeneratorRepository extends JpaRepository<LoadGenerator, UUID> {
    @Query("select g from LoadGenerator g where g.status = :status and g.lastHeartbeatAt >= :cutoff "
            + "order by g.maxVus asc, g.id asc")
    List<LoadGenerator> findHealthyAvailable(@Param("status") GeneratorStatus status,
            @Param("cutoff") java.time.Instant cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from LoadGenerator g where g.id = :id")
    Optional<LoadGenerator> findByIdForUpdate(@Param("id") UUID id);
}
