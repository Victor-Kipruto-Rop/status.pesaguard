package com.pesaguard.backend.analytics.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.analytics.domain.UsageBucket;
import com.pesaguard.backend.analytics.domain.UsageGranularity;

public interface UsageBucketRepository extends JpaRepository<UsageBucket, UUID> {

    @Query("""
            select b from UsageBucket b
            where b.organizationId = :organizationId
              and b.granularity = :granularity
              and b.windowStart >= :from
              and b.windowStart < :to
              and (:projectId is null or b.projectId = :projectId)
              and (:environmentId is null or b.environmentId = :environmentId)
              and (:apiKeyId is null or b.apiKeyId = :apiKeyId)
            order by b.windowStart asc, b.projectId asc, b.environmentId asc, b.endpoint asc, b.method asc
            """)
    Page<UsageBucket> findExportRowsForOrganization(
            @Param("organizationId") UUID organizationId,
            @Param("granularity") UsageGranularity granularity,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("projectId") UUID projectId,
            @Param("environmentId") UUID environmentId,
            @Param("apiKeyId") UUID apiKeyId,
            Pageable pageable);

    @Query("""
            select b from UsageBucket b
            where b.organizationId = :organizationId
              and b.projectId in :projectIds
              and b.granularity = :granularity
              and b.windowStart >= :from
              and b.windowStart < :to
              and (:projectId is null or b.projectId = :projectId)
              and (:environmentId is null or b.environmentId = :environmentId)
              and (:apiKeyId is null or b.apiKeyId = :apiKeyId)
            order by b.windowStart asc, b.projectId asc, b.environmentId asc, b.endpoint asc, b.method asc
            """)
    Page<UsageBucket> findExportRowsForProjects(
            @Param("organizationId") UUID organizationId,
            @Param("projectIds") java.util.Set<UUID> projectIds,
            @Param("granularity") UsageGranularity granularity,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("projectId") UUID projectId,
            @Param("environmentId") UUID environmentId,
            @Param("apiKeyId") UUID apiKeyId,
            Pageable pageable);

    Optional<UsageBucket> findByGranularityAndWindowStartAndOrganizationIdAndProjectIdAndEnvironmentIdAndApiKeyIdAndOauthApplicationIdAndEndpointAndMethod(
            UsageGranularity granularity, Instant windowStart, UUID organizationId,
            UUID projectId, UUID environmentId, UUID apiKeyId, UUID oauthApplicationId,
            String endpoint, String method);

    /**
     * Every bucket for an organization in a granularity and window range.
     *
     * <p>Always organization-scoped. Usage is tenant data; a query without this
     * predicate would expose one tenant's traffic to another.
     */
    @Query("""
            select b from UsageBucket b
            where b.organizationId = :organizationId
              and b.granularity = :granularity
              and b.windowStart >= :from
              and b.windowStart < :to
            order by b.windowStart asc
            """)
    List<UsageBucket> findForOrganization(
            @Param("organizationId") UUID organizationId,
            @Param("granularity") UsageGranularity granularity,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** Usage buckets restricted to projects the authenticated user may read. */
    @Query("""
            select b from UsageBucket b
            where b.organizationId = :organizationId
              and b.projectId in :projectIds
              and b.granularity = :granularity
              and b.windowStart >= :from
              and b.windowStart < :to
            order by b.windowStart asc
            """)
    List<UsageBucket> findForOrganizationAndProjects(
            @Param("organizationId") UUID organizationId,
            @Param("projectIds") Set<UUID> projectIds,
            @Param("granularity") UsageGranularity granularity,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** Distinct endpoints used by an organization, for the usage breakdown. */
    @Query("""
            select distinct b.endpoint from UsageBucket b
            where b.organizationId = :organizationId
              and b.granularity = :granularity
              and b.windowStart >= :from and b.windowStart < :to
            """)
    List<String> findDistinctEndpoints(
            @Param("organizationId") UUID organizationId,
            @Param("granularity") UsageGranularity granularity,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** Distinct endpoints restricted to authorized projects and an optional environment. */
    @Query("""
            select distinct b.endpoint from UsageBucket b
            where b.organizationId = :organizationId
              and b.projectId in :projectIds
              and (:environmentId is null or b.environmentId = :environmentId)
              and b.granularity = :granularity
              and b.windowStart >= :from and b.windowStart < :to
            """)
    List<String> findDistinctEndpointsForProjects(
            @Param("organizationId") UUID organizationId,
            @Param("projectIds") Set<UUID> projectIds,
            @Param("environmentId") UUID environmentId,
            @Param("granularity") UsageGranularity granularity,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** Windows that exist at this granularity, so the worker knows what to rebuild. */
    @Query("""
            select distinct b.windowStart from UsageBucket b
            where b.granularity = :granularity
              and b.windowStart >= :from and b.windowStart < :to
            """)
    List<Instant> findWindowStarts(
            @Param("granularity") UsageGranularity granularity,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
