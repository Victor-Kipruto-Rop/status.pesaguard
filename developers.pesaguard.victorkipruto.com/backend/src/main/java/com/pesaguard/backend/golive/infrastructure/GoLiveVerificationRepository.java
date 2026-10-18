package com.pesaguard.backend.golive.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.golive.domain.GoLiveVerification;

public interface GoLiveVerificationRepository extends JpaRepository<GoLiveVerification, UUID> {

    List<GoLiveVerification> findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByVerifiedAtDesc(
            UUID organizationId, UUID projectId, UUID environmentId, Pageable pageable);

    Optional<GoLiveVerification> findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
            UUID id, UUID organizationId, UUID projectId, UUID environmentId);

    Optional<GoLiveVerification> findByOrganizationIdAndProjectIdAndEnvironmentIdAndIdempotencyKey(
            UUID organizationId, UUID projectId, UUID environmentId, String idempotencyKey);
}
