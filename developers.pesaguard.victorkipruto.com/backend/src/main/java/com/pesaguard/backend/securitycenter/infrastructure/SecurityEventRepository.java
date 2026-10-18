package com.pesaguard.backend.securitycenter.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.pesaguard.backend.securitycenter.domain.SecurityEvent.Resolution;

public interface SecurityEventRepository extends JpaRepository<SecurityEventEntity, UUID> {

    /**
     * Every lookup is organization-scoped.
     *
     * <p>Never expose a find-by-id alone: an id is not a secret, and an unscoped
     * lookup would let one tenant read another's security signals by guessing a
     * UUID. A miss returns empty rather than another tenant's row.
     */
    Optional<SecurityEventEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<SecurityEventEntity> findByOrganizationIdAndResolutionOrderByDetectedAtDesc(
            UUID organizationId, Resolution resolution, Pageable pageable);

    Page<SecurityEventEntity> findByOrganizationIdOrderByDetectedAtDesc(
            UUID organizationId, Pageable pageable);

    List<SecurityEventEntity> findByOrganizationIdAndTypeOrderByDetectedAtDesc(
            UUID organizationId, com.pesaguard.backend.securitycenter.domain.SecurityEventType type);

    long countByOrganizationIdAndResolution(UUID organizationId, Resolution resolution);

    boolean existsByOrganizationIdAndTypeAndSubjectIdAndSubjectKindAndDetectedAtAfter(
            UUID organizationId,
            com.pesaguard.backend.securitycenter.domain.SecurityEventType type,
            UUID subjectId,
            String subjectKind,
            java.time.Instant detectedAfter);
}
