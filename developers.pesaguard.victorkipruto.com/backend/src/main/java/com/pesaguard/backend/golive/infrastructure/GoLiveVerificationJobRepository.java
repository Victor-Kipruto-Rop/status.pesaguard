package com.pesaguard.backend.golive.infrastructure;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.pesaguard.backend.golive.domain.GoLiveVerificationJob;

public interface GoLiveVerificationJobRepository extends JpaRepository<GoLiveVerificationJob, UUID> {

    Optional<GoLiveVerificationJob> findByOrganizationIdAndProjectIdAndEnvironmentIdAndIdempotencyKey(
            UUID organizationId, UUID projectId, UUID environmentId, String idempotencyKey);

    Optional<GoLiveVerificationJob> findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
            UUID id, UUID organizationId, UUID projectId, UUID environmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<GoLiveVerificationJob> findFirstByStatusOrderByQueuedAtAsc(String status);
}
