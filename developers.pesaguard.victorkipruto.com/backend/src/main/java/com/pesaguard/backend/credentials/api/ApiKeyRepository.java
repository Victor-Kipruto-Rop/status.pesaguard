package com.pesaguard.backend.credentials.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    @Query("select key from ApiKey key where key.organizationId = :organizationId "
            + "and key.status in (com.pesaguard.backend.credentials.api.ApiKeyStatus.ACTIVE, "
            + "com.pesaguard.backend.credentials.api.ApiKeyStatus.SUSPENDED) "
            + "and (key.expiresAt is null or key.expiresAt > :now) "
            + "and (lower(key.name) like lower(concat('%', :query, '%')) "
            + "or lower(key.keyPrefix) like lower(concat('%', :query, '%'))) "
            + "order by key.name asc")
    Page<ApiKey> searchOrganizationKeys(@Param("organizationId") UUID organizationId,
            @Param("query") String query, @Param("now") Instant now, Pageable pageable);

    @Query("select key from ApiKey key where key.organizationId = :organizationId "
            + "and key.projectId in :projectIds "
            + "and key.status in (com.pesaguard.backend.credentials.api.ApiKeyStatus.ACTIVE, "
            + "com.pesaguard.backend.credentials.api.ApiKeyStatus.SUSPENDED) "
            + "and (key.expiresAt is null or key.expiresAt > :now) "
            + "and (lower(key.name) like lower(concat('%', :query, '%')) "
            + "or lower(key.keyPrefix) like lower(concat('%', :query, '%'))) "
            + "order by key.name asc")
    Page<ApiKey> searchVisibleKeys(@Param("organizationId") UUID organizationId,
            @Param("projectIds") java.util.Set<UUID> projectIds,
            @Param("query") String query, @Param("now") Instant now, Pageable pageable);

    Optional<ApiKey> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ApiKey> findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
            UUID organizationId, UUID projectId, UUID environmentId);

    List<ApiKey> findByOrganizationIdAndProjectIdAndEnvironmentIdAndStatusOrderByCreatedAtDesc(
            UUID organizationId, UUID projectId, UUID environmentId, ApiKeyStatus status);

    long countByEnvironmentIdAndStatus(UUID environmentId, ApiKeyStatus status);

    @Query("select count(key) from ApiKey key where key.environmentId = :environmentId "
            + "and key.status = com.pesaguard.backend.credentials.api.ApiKeyStatus.ACTIVE "
            + "and (key.expiresAt is null or key.expiresAt > :now)")
    long countUsableProductionKeys(@Param("environmentId") UUID environmentId, @Param("now") Instant now);

    Optional<ApiKey> findBySecretHash(String secretHash);

    Optional<ApiKey> findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
            UUID id, UUID organizationId, UUID projectId, UUID environmentId);

    boolean existsByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
            UUID id, UUID organizationId, UUID projectId, UUID environmentId);

    List<ApiKey> findByCreatedByOrderByCreatedAtDesc(UUID createdBy);
}
