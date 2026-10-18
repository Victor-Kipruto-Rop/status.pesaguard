package com.pesaguard.backend.project.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

import com.pesaguard.backend.project.domain.ProjectMember;
import com.pesaguard.backend.project.domain.ProjectMemberRole;
import com.pesaguard.backend.project.domain.ProjectMemberStatus;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    Optional<ProjectMember> findByIdAndProjectId(UUID id, UUID projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from ProjectMember member where member.id = :memberId and member.projectId = :projectId")
    Optional<ProjectMember> findByIdAndProjectIdForUpdate(@Param("memberId") UUID memberId,
            @Param("projectId") UUID projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from ProjectMember member where member.projectId = :projectId and member.userId = :userId")
    Optional<ProjectMember> findByProjectIdAndUserIdForUpdate(@Param("projectId") UUID projectId,
            @Param("userId") UUID userId);

    List<ProjectMember> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    @Query("""
            select member.userId as userId, project.name as projectName
            from ProjectMember member join Project project on project.id = member.projectId
            where member.organizationId = :organizationId
              and member.userId in :userIds
              and member.status = com.pesaguard.backend.project.domain.ProjectMemberStatus.ACTIVE
              and project.organizationId = :organizationId
            order by project.name
            """)
    List<ProjectAssignmentProjection> findActiveAssignmentsForOrganizationMembers(
            @Param("organizationId") UUID organizationId, @Param("userIds") List<UUID> userIds);

    @Query("""
            select member from ProjectMember member
            where member.organizationId = :organizationId
              and member.userId = :userId
              and member.status = com.pesaguard.backend.project.domain.ProjectMemberStatus.ACTIVE
            """)
    List<ProjectMember> findActiveByOrganizationIdAndUserId(
            @Param("organizationId") UUID organizationId, @Param("userId") UUID userId);

    @Query("""
            select member from ProjectMember member
            where member.projectId = :projectId
              and member.userId = :userId
              and member.status = com.pesaguard.backend.project.domain.ProjectMemberStatus.ACTIVE
              and member.role in :roles
            """)
    List<ProjectMember> findActiveByProjectIdAndUserIdAndRoleIn(
            @Param("projectId") UUID projectId,
            @Param("userId") UUID userId,
            @Param("roles") List<ProjectMemberRole> roles);

    boolean existsByProjectIdAndUserIdAndStatus(
            UUID projectId, UUID userId, ProjectMemberStatus status);
}
