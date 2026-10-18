package com.pesaguard.backend.events.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.events.domain.EventDelivery;

public interface EventDeliveryRepository extends JpaRepository<EventDelivery, UUID> {

    Page<EventDelivery> findByOrganizationIdOrderByCreatedAtDesc(
            UUID organizationId, Pageable pageable);

    Page<EventDelivery> findByOrganizationIdAndProjectIdOrderByCreatedAtDesc(
            UUID organizationId, UUID projectId, Pageable pageable);

    Page<EventDelivery> findByOrganizationIdAndProjectIdAndEnvironmentIdOrderByCreatedAtDesc(
            UUID organizationId, UUID projectId, UUID environmentId, Pageable pageable);

    Page<EventDelivery> findByOrganizationIdAndProjectIdInOrderByCreatedAtDesc(
            UUID organizationId, java.util.Collection<UUID> projectIds, Pageable pageable);

    Optional<EventDelivery> findTopByEventIdAndSubscriptionIdOrderByAttemptDesc(
            UUID eventId, UUID subscriptionId);

    Optional<EventDelivery> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
