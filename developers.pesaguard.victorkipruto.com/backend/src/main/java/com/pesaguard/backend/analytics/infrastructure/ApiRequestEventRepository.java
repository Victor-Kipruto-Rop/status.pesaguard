package com.pesaguard.backend.analytics.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.analytics.domain.ApiRequestEvent;

public interface ApiRequestEventRepository extends JpaRepository<ApiRequestEvent, UUID> {

    boolean existsByRequestId(String requestId);

    boolean existsByOrganizationIdAndEnvironmentIdAndStatusCodeBetween(
            UUID organizationId, UUID environmentId, int minimumStatusCode, int maximumStatusCode);

    Optional<ApiRequestEvent> findByRequestId(String requestId);

    Optional<ApiRequestEvent> findByRequestIdAndOrganizationId(String requestId, UUID organizationId);

    @Query("""
            select e from ApiRequestEvent e
            where e.organizationId = :organizationId
              and (:projectId is null or e.projectId = :projectId)
              and (:environmentId is null or e.environmentId = :environmentId)
              and (:statusCode is null or e.statusCode = :statusCode)
              and (:method is null or e.method = :method)
              and (:requestId is null or e.requestId = :requestId)
              and (:apiKeyId is null or e.apiKeyId = :apiKeyId)
              and e.occurredAt >= :from
              and e.occurredAt < :to
            order by e.occurredAt desc
            """)
    Page<ApiRequestEvent> searchOrganizationRequests(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("environmentId") UUID environmentId,
            @Param("statusCode") Integer statusCode,
            @Param("method") String method,
            @Param("requestId") String requestId,
            @Param("apiKeyId") UUID apiKeyId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /** Request search restricted to projects assigned to the authenticated member. */
    @Query("""
            select e from ApiRequestEvent e
            where e.organizationId = :organizationId
              and e.projectId in :projectIds
              and (:projectId is null or e.projectId = :projectId)
              and (:environmentId is null or e.environmentId = :environmentId)
              and (:statusCode is null or e.statusCode = :statusCode)
              and (:method is null or e.method = :method)
              and (:requestId is null or e.requestId = :requestId)
              and (:apiKeyId is null or e.apiKeyId = :apiKeyId)
              and e.occurredAt >= :from
              and e.occurredAt < :to
            order by e.occurredAt desc
            """)
    Page<ApiRequestEvent> searchOrganizationRequestsForProjects(
            @Param("organizationId") UUID organizationId,
            @Param("projectIds") Set<UUID> projectIds,
            @Param("projectId") UUID projectId,
            @Param("environmentId") UUID environmentId,
            @Param("statusCode") Integer statusCode,
            @Param("method") String method,
            @Param("requestId") String requestId,
            @Param("apiKeyId") UUID apiKeyId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /**
     * Events inside a half-open window, bounded by {@code organizationId}.
     *
     * <p>The organization predicate is not optional. Every query from this
     * repository is tenant-scoped, and a query without it would return another
     * tenant's traffic.
     *
     * <p>Range is {@code [from, to)} so an event on a boundary belongs to exactly
     * one window.
     */
    @Query("""
            select e from ApiRequestEvent e
            where e.organizationId = :organizationId
              and e.occurredAt >= :from
              and e.occurredAt < :to
            order by e.occurredAt asc
            """)
    List<ApiRequestEvent> findInWindow(@Param("organizationId") UUID organizationId,
            @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /**
     * Distinct dimension sets present in a window, so the rollup knows what to
     * aggregate without scanning the raw events first.
     */
    @Query("""
            select distinct e.organizationId, e.projectId, e.environmentId,
                   e.apiKeyId, e.oauthApplicationId, e.endpoint, e.method
            from ApiRequestEvent e
            where e.occurredAt >= :from and e.occurredAt < :to
            """)
    List<Object[]> findDistinctDimensions(@Param("from") Instant from, @Param("to") Instant to);

    /** Tenant-wide request counts by environment for internal quota alerting. */
    @Query("""
            select e.organizationId, e.environmentId, count(e)
            from ApiRequestEvent e
            where e.environmentId is not null
              and e.occurredAt >= :from and e.occurredAt < :to
            group by e.organizationId, e.environmentId
            """)
    List<Object[]> countByEnvironmentBetween(@Param("from") Instant from, @Param("to") Instant to);
}
