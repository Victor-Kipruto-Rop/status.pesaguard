package com.pesaguard.backend.integration.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.integration.domain.Integration;

import jakarta.persistence.LockModeType;

public interface IntegrationRepository extends JpaRepository<Integration, UUID> {

    List<Integration> findByOrganizationIdAndProjectIdOrderByCreatedAtDesc(UUID organizationId, UUID projectId);

    Optional<Integration> findByIdAndOrganizationIdAndProjectId(
            UUID id, UUID organizationId, UUID projectId);

    Optional<Integration> findByEnvironmentIdAndType(UUID environmentId, String type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select integration from Integration integration where integration.id = :id "
            + "and integration.organizationId = :organizationId and integration.projectId = :projectId")
    Optional<Integration> findByIdAndOrganizationIdAndProjectIdForUpdate(
            @Param("id") UUID id, @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId);
}
