package com.pesaguard.backend.outbox.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.outbox.domain.OutboxEvent;
import com.pesaguard.backend.outbox.domain.OutboxStatus;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims up to {@code limit} events ready to publish, oldest first.
     *
     * <p>Oldest first so events are published in the order they were committed,
     * which is what per-tenant ordering requires.
     *
     * <p>This is a plain read rather than {@code SELECT ... FOR UPDATE SKIP
     * LOCKED}. A locking claim would stop two publishers taking the same row,
     * but it would also make a crashed publisher hold locks until the
     * transaction rolled back, and it would serialise publishers against the
     * domain writes happening on the same table. At-least-once delivery already
     * requires consumers to be idempotent, so a duplicate publish is the
     * tolerated failure rather than the one being optimised away.
     */
    @Query("select e from OutboxEvent e where e.status = com.pesaguard.backend.outbox.domain.OutboxStatus.PENDING "
            + "and e.nextAttemptAt <= :now order by e.createdAt asc")
    List<OutboxEvent> findDue(@Param("now") Instant now, Pageable pageable);

    Optional<OutboxEvent> findByEventId(UUID eventId);

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    List<OutboxEvent> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    List<OutboxEvent> findByOrganizationIdAndProjectIdOrderByCreatedAtDesc(
            UUID organizationId, UUID projectId, Pageable pageable);

    Page<OutboxEvent> findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
            UUID organizationId, UUID projectId, UUID environmentId, Pageable pageable);

    List<OutboxEvent> findByOrganizationIdAndProjectIdInOrderByCreatedAtDesc(
            UUID organizationId, java.util.Collection<UUID> projectIds, Pageable pageable);

    /** Dead letters for one tenant, for operator inspection. */
    List<OutboxEvent> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            UUID organizationId, OutboxStatus status, Pageable pageable);

    long countByStatus(OutboxStatus status);
}
