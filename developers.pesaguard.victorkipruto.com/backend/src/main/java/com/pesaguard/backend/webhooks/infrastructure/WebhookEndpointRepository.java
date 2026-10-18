package com.pesaguard.backend.webhooks.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.webhooks.domain.WebhookEndpoint;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    @Query("select endpoint from WebhookEndpoint endpoint where endpoint.organizationId = :organizationId "
            + "and lower(endpoint.name) like lower(concat('%', :query, '%')) "
            + "order by endpoint.name asc")
    Page<WebhookEndpoint> searchOrganizationEndpoints(@Param("organizationId") UUID organizationId,
            @Param("query") String query, Pageable pageable);

    @Query("select endpoint from WebhookEndpoint endpoint where endpoint.organizationId = :organizationId "
            + "and endpoint.projectId in :projectIds "
            + "and lower(endpoint.name) like lower(concat('%', :query, '%')) "
            + "order by endpoint.name asc")
    Page<WebhookEndpoint> searchVisibleEndpoints(@Param("organizationId") UUID organizationId,
            @Param("projectIds") java.util.Set<UUID> projectIds,
            @Param("query") String query, Pageable pageable);

    List<WebhookEndpoint> findByOrganizationIdAndProjectIdAndEnvironmentIdAndStatusNotOrderByCreatedAtDesc(
            UUID organizationId, UUID projectId, UUID environmentId, String status, Pageable pageable);

    Optional<WebhookEndpoint> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndProjectIdAndStatus(
            UUID organizationId, UUID projectId, String status);

    Optional<WebhookEndpoint> findByIdAndOrganizationIdAndProjectId(UUID id,
            UUID organizationId, UUID projectId);

    Optional<WebhookEndpoint> findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
            UUID id, UUID organizationId, UUID projectId, UUID environmentId);

    List<WebhookEndpoint> findByIdInAndOrganizationIdAndProjectIdAndStatus(
            List<UUID> ids, UUID organizationId, UUID projectId, String status);
}
