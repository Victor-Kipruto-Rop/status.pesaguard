package com.pesaguard.backend.rbac.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.rbac.domain.RoleAssignment;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, UUID> {

    @Query("""
            select assignment from RoleAssignment assignment
            where assignment.organizationId = :organizationId
              and assignment.userId = :userId
              and assignment.revokedAt is null
            """)
    List<RoleAssignment> findActiveByOrganizationIdAndUserId(
            @Param("organizationId") UUID organizationId, @Param("userId") UUID userId);

    @Query("""
            select assignment from RoleAssignment assignment
            where assignment.organizationId = :organizationId
              and assignment.userId = :userId
              and assignment.roleId = :roleId
              and assignment.revokedAt is null
            """)
    Optional<RoleAssignment> findActiveByOrganizationIdAndUserIdAndRoleId(
            @Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId,
            @Param("roleId") UUID roleId);

    @Query("""
            select assignment from RoleAssignment assignment
            where assignment.organizationId = :organizationId
            order by assignment.assignedAt desc
            """)
    List<RoleAssignment> findAllByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("""
            select assignment from RoleAssignment assignment
            where assignment.id = :id and assignment.organizationId = :organizationId
            """)
    Optional<RoleAssignment> findByIdAndOrganizationId(
            @Param("id") UUID id, @Param("organizationId") UUID organizationId);
}