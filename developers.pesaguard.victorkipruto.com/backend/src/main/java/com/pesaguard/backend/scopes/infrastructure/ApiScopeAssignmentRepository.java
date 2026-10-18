package com.pesaguard.backend.scopes.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.scopes.domain.ApiScopeAssignment;

public interface ApiScopeAssignmentRepository extends JpaRepository<ApiScopeAssignment, UUID> {

    Optional<ApiScopeAssignment> findByApiKeyIdAndScopeName(UUID apiKeyId, String scopeName);

    List<ApiScopeAssignment> findByApiKeyIdAndRevokedAtIsNullOrderByGrantedAtDesc(UUID apiKeyId);

    List<ApiScopeAssignment> findByOrganizationIdOrderByGrantedAtDesc(UUID organizationId);

    List<ApiScopeAssignment> findByApiKeyIdOrderByGrantedAtDesc(UUID apiKeyId);
}