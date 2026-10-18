package com.pesaguard.backend.golive.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.golive.domain.GoLiveLaunch;

public interface GoLiveLaunchRepository extends JpaRepository<GoLiveLaunch, UUID> {

    Optional<GoLiveLaunch> findByOrganizationIdAndProjectIdAndEnvironmentIdAndIdempotencyKey(
            UUID organizationId, UUID projectId, UUID environmentId, String idempotencyKey);

    List<GoLiveLaunch> findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByStartedAtDesc(
            UUID organizationId, UUID projectId, UUID environmentId, Pageable pageable);

    boolean existsByOrganizationIdAndProjectIdAndEnvironmentIdAndStatus(
            UUID organizationId, UUID projectId, UUID environmentId, String status);

    Optional<GoLiveLaunch> findFirstByOrganizationIdAndProjectIdAndEnvironmentIdAndStatusOrderByStartedAtDesc(
            UUID organizationId, UUID projectId, UUID environmentId, String status);

}
