package com.pesaguard.backend.notifications.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import com.pesaguard.backend.notifications.domain.NotificationCategory;
import com.pesaguard.backend.notifications.domain.NotificationSeverity;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    /**
     * A user's notifications, newest first.
     *
     * <p>Always organization-scoped as well as user-scoped: a user id taken from a
     * path must not be the only guard, because it is the tenant boundary that
     * actually matters here.
     */
    List<NotificationEntity> findByOrganizationIdAndUserIdOrderByCreatedAtDesc(
            UUID organizationId, UUID userId);

    Page<NotificationEntity> findByOrganizationIdAndUserIdOrderByCreatedAtDesc(
            UUID organizationId, UUID userId, Pageable pageable);

    @Query("""
            select n from NotificationEntity n
            where n.organizationId = :organizationId
              and n.userId = :userId
              and (:category is null or n.category = :category)
              and (:severity is null or n.severity = :severity)
              and (:unreadOnly = false or n.readAt is null)
            order by n.createdAt desc
            """)
    Page<NotificationEntity> findInbox(@Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId, @Param("category") NotificationCategory category,
            @Param("severity") NotificationSeverity severity,
            @Param("unreadOnly") boolean unreadOnly, Pageable pageable);

    @Query("""
            select count(n) from NotificationEntity n
            where n.organizationId = :organizationId
              and n.userId = :userId
              and n.readAt is null
              and (:category is null or n.category = :category)
              and (:severity is null or n.severity = :severity)
            """)
    long countUnreadMatching(@Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId, @Param("category") NotificationCategory category,
            @Param("severity") NotificationSeverity severity);

    java.util.Optional<NotificationEntity> findByIdAndOrganizationIdAndUserId(
            UUID id, UUID organizationId, UUID userId);

    long countByOrganizationIdAndUserIdAndReadAtIsNull(UUID organizationId, UUID userId);

    @Modifying
    @Transactional
    @Query("update NotificationEntity n set n.readAt = :readAt "
            + "where n.organizationId = :organizationId and n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId, @Param("readAt") java.time.Instant readAt);

    @Modifying
    @Transactional
    @Query("delete from NotificationEntity n where n.organizationId = :organizationId "
            + "and n.userId = :userId and n.readAt is not null")
    int deleteReadByOrganizationIdAndUserId(@Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId);

    long countByOrganizationIdAndUserIdAndType(UUID organizationId, UUID userId,
            com.pesaguard.backend.notifications.domain.NotificationType type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NotificationEntity n where n.id = :id")
    java.util.Optional<NotificationEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            select n.id from notifications n
            where exists (
                select 1 from notification_channel_deliveries d
                where d.notification_id = n.id
                  and d.state = 'RETRY_SCHEDULED'
                  and d.next_attempt_at <= :now
            )
            order by n.created_at asc
            limit :limit
            """, nativeQuery = true)
    List<UUID> findDueRetryIds(@Param("now") java.time.Instant now, @Param("limit") int limit);
}
