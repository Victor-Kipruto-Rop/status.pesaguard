package com.pesaguard.backend.notifications.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface NotificationQueueEventRepository
        extends JpaRepository<NotificationQueueEventEntity, UUID> {

    @Modifying
    @Query(value = """
            insert into notification_event_queue
                (id, organization_id, user_id, type, severity, subject, body,
                 resource_type, resource_id, action_url, deduplication_key,
                 state, attempts, available_at, created_at)
            values
                (:id, :organizationId, :userId, :type, :severity, :subject, :body,
                 :resourceType, :resourceId, :actionUrl, :deduplicationKey,
                 'PENDING', 0, :availableAt, now())
            on conflict (organization_id, user_id, type, deduplication_key)
                where deduplication_key is not null do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId, @Param("type") String type,
            @Param("severity") String severity, @Param("subject") String subject,
            @Param("body") String body, @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId, @Param("actionUrl") String actionUrl,
            @Param("deduplicationKey") String deduplicationKey,
            @Param("availableAt") Instant availableAt);

    @Query(value = """
            select id from notification_event_queue
            where state = 'PENDING' and available_at <= :now
            order by case severity
                when 'SECURITY' then 0
                when 'CRITICAL' then 1
                when 'ERROR' then 2
                when 'WARNING' then 3
                else 4
            end, created_at asc
            limit :limit
            """, nativeQuery = true)
    List<UUID> findReadyIds(@Param("now") Instant now, @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from NotificationQueueEventEntity e where e.id = :id")
    Optional<NotificationQueueEventEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<NotificationQueueEventEntity> findByOrganizationIdAndUserIdAndTypeAndDeduplicationKey(
            UUID organizationId, UUID userId,
            com.pesaguard.backend.notifications.domain.NotificationType type,
            String deduplicationKey);
}
