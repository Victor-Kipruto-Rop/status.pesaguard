package com.pesaguard.backend.environment.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.project.domain.ProjectStatus;
import jakarta.persistence.LockModeType;

public interface ProjectEnvironmentRepository extends JpaRepository<ProjectEnvironment, UUID> {

    Optional<ProjectEnvironment> findByIdAndOrganizationIdAndProjectId(
            UUID id, UUID organizationId, UUID projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select environment from ProjectEnvironment environment where environment.id = :id "
            + "and environment.organizationId = :organizationId and environment.projectId = :projectId")
    Optional<ProjectEnvironment> findByIdAndOrganizationIdAndProjectIdForUpdate(
            @Param("id") UUID id, @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId);

    Optional<ProjectEnvironment> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("select environment from ProjectEnvironment environment where "
            + "environment.organizationId = :organizationId and "
            + "lower(environment.name) like lower(concat('%', :query, '%')) "
            + "order by environment.name asc")
    Page<ProjectEnvironment> searchOrganizationEnvironments(
            @Param("organizationId") UUID organizationId,
            @Param("query") String query, Pageable pageable);

    @Query("select environment from ProjectEnvironment environment where "
            + "environment.organizationId = :organizationId and "
            + "environment.projectId in :projectIds and "
            + "lower(environment.name) like lower(concat('%', :query, '%')) "
            + "order by environment.name asc")
    Page<ProjectEnvironment> searchVisibleEnvironments(
            @Param("organizationId") UUID organizationId,
            @Param("projectIds") java.util.Set<UUID> projectIds,
            @Param("query") String query, Pageable pageable);

    List<ProjectEnvironment> findByOrganizationIdAndProjectIdOrderByCreatedAtAsc(
            UUID organizationId, UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    boolean existsByProjectIdAndType(UUID projectId, EnvironmentType type);

    Optional<ProjectEnvironment> findByProjectIdAndType(UUID projectId, EnvironmentType type);

    Optional<ProjectEnvironment> findFirstByOrganizationIdAndProjectIdAndStatusOrderByCreatedAtAsc(
            UUID organizationId, UUID projectId,
            com.pesaguard.backend.environment.domain.EnvironmentStatus status);

    long countByProjectId(UUID projectId);

    @Query(value = """
            select exists (
                select 1
                from project_environments environment
                join projects project on project.id = environment.project_id
                where environment.organization_id = :organizationId
                  and environment.status = 'ACTIVE'
                  and project.status = :projectStatus
            )
            """, nativeQuery = true)
    boolean existsActiveEnvironmentForOrganization(
            @Param("organizationId") UUID organizationId,
            @Param("projectStatus") String projectStatus);
}
