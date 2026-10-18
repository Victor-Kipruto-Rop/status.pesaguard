package com.pesaguard.backend.scopes.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.scopes.domain.ApiAccessDecisionRecord;

public interface ApiAccessDecisionRepository extends JpaRepository<ApiAccessDecisionRecord, UUID> {

    List<ApiAccessDecisionRecord> findByOrganizationIdAndAllowedFalseOrderByDecidedAtDesc(
            UUID organizationId, Pageable pageable);

    List<ApiAccessDecisionRecord> findByApiKeyIdOrderByDecidedAtDesc(UUID apiKeyId, Pageable pageable);

    long countByOrganizationIdAndAllowedFalse(UUID organizationId);
}