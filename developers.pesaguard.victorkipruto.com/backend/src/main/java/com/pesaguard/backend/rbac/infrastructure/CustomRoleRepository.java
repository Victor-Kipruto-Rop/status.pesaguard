package com.pesaguard.backend.rbac.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.rbac.domain.CustomRole;
import com.pesaguard.backend.rbac.domain.CustomRoleStatus;

public interface CustomRoleRepository extends JpaRepository<CustomRole, UUID> {

    List<CustomRole> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<CustomRole> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            UUID organizationId, CustomRoleStatus status);

    Optional<CustomRole> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<CustomRole> findByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);

    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);
}