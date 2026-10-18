package com.pesaguard.backend.audit.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.audit.domain.AuditEvent;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Optional<AuditEvent> findTopByOrganizationIdOrderBySequenceNumberDesc(UUID organizationId);

    Page<AuditEvent> findByOrganizationIdOrderBySequenceNumberDesc(UUID organizationId, Pageable pageable);

    Page<AuditEvent> findByOrganizationIdAndProjectIdOrderBySequenceNumberDesc(
            UUID organizationId, UUID projectId, Pageable pageable);

    List<AuditEvent> findByOrganizationIdOrderBySequenceNumberAsc(UUID organizationId);
}
