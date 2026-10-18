package com.pesaguard.backend.project.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

import com.pesaguard.backend.project.domain.Project;
import com.pesaguard.backend.project.domain.ProjectStatus;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select project from Project project where project.id = :id and project.organizationId = :organizationId")
    Optional<Project> findByIdAndOrganizationIdForUpdate(@Param("id") UUID id,
            @Param("organizationId") UUID organizationId);

    Optional<Project> findByOrganizationIdAndSlug(UUID organizationId, String slug);

    Optional<Project> findFirstByOrganizationIdAndStatusOrderByCreatedAtAsc(
            UUID organizationId, ProjectStatus status);

    Page<Project> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    @Query("select project from Project project where project.organizationId = :organizationId "
            + "and (lower(project.name) like lower(concat('%', :query, '%')) "
            + "or lower(project.slug) like lower(concat('%', :query, '%'))) order by project.name asc")
    Page<Project> searchOrganizationProjects(@Param("organizationId") UUID organizationId,
            @Param("query") String query, Pageable pageable);

    @Query("select project from Project project where project.organizationId = :organizationId "
            + "and project.id in :projectIds "
            + "and (lower(project.name) like lower(concat('%', :query, '%')) "
            + "or lower(project.slug) like lower(concat('%', :query, '%'))) order by project.name asc")
    Page<Project> searchVisibleProjects(@Param("organizationId") UUID organizationId,
            @Param("query") String query, @Param("projectIds") java.util.Set<UUID> projectIds,
            Pageable pageable);

    /**
     * Project-level members may only list projects for which they have an active
     * membership. The EXISTS clause avoids duplicate projects when membership
     * data is joined and keeps pagination/counting stable.
     */
    @Query(value = """
            select project from Project project
            where project.organizationId = :organizationId
              and exists (
                select member.id from ProjectMember member
                where member.projectId = project.id
                  and member.organizationId = :organizationId
                  and member.userId = :userId
                  and member.status = com.pesaguard.backend.project.domain.ProjectMemberStatus.ACTIVE
              )
            order by project.createdAt desc
            """,
            countQuery = """
            select count(project) from Project project
            where project.organizationId = :organizationId
              and exists (
                select member.id from ProjectMember member
                where member.projectId = project.id
                  and member.organizationId = :organizationId
                  and member.userId = :userId
                  and member.status = com.pesaguard.backend.project.domain.ProjectMemberStatus.ACTIVE
              )
            """)
    Page<Project> findVisibleToProjectMember(
            @Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId,
            Pageable pageable);

    boolean existsByOrganizationIdAndSlug(UUID organizationId, String slug);

    boolean existsByOrganizationIdAndStatus(UUID organizationId, ProjectStatus status);
}
