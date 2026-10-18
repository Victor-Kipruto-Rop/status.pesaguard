package com.pesaguard.backend.events.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.events.domain.EventDelivery;
import com.pesaguard.backend.events.domain.EventSubscription;
import com.pesaguard.backend.events.domain.SubscriptionStatus;
import com.pesaguard.backend.outbox.domain.OutboxEvent;

public interface EventSubscriptionRepository extends JpaRepository<EventSubscription, UUID> {

    List<EventSubscription> findByOrganizationIdOrderByCreatedAtDesc(
            UUID organizationId, Pageable pageable);

    List<EventSubscription> findByOrganizationIdAndProjectIdOrderByCreatedAtDesc(
            UUID organizationId, UUID projectId, Pageable pageable);

    List<EventSubscription> findByOrganizationIdAndProjectIdInOrderByCreatedAtDesc(
            UUID organizationId, java.util.Collection<UUID> projectIds, Pageable pageable);

    Page<EventSubscription> findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
            UUID organizationId, UUID projectId, UUID environmentId, Pageable pageable);

    List<EventSubscription> findByOrganizationIdAndProjectIdAndEventTypeAndStatus(
            UUID organizationId, UUID projectId, String eventType, SubscriptionStatus status);

    boolean existsByEndpointIdAndOrganizationIdAndStatusNot(
            String endpointId, UUID organizationId, SubscriptionStatus status);

    java.util.Optional<EventSubscription> findByIdAndOrganizationId(
            UUID id, UUID organizationId);

    @Query("""
            select e, s from OutboxEvent e, EventSubscription s
            where e.organizationId = s.organizationId
              and e.projectId = s.projectId
              and e.environmentId = s.environmentId
              and e.eventType = s.eventType
              and s.status = com.pesaguard.backend.events.domain.SubscriptionStatus.ACTIVE
              and (
                not exists (
                    select d from EventDelivery d
                    where d.eventId = e.eventId and d.subscriptionId = s.id
                )
                or exists (
                    select d from EventDelivery d
                    where d.eventId = e.eventId
                      and d.subscriptionId = s.id
                      and d.attempt = (
                        select max(previous.attempt) from EventDelivery previous
                        where previous.eventId = e.eventId
                          and previous.subscriptionId = s.id
                      )
                      and d.status = com.pesaguard.backend.events.domain.DeliveryStatus.RETRY_SCHEDULED
                      and d.nextAttemptAt <= :now
                )
              )
            order by e.createdAt asc
            """)
    List<Object[]> findDueDeliveries(@Param("now") Instant now, Pageable pageable);
}
