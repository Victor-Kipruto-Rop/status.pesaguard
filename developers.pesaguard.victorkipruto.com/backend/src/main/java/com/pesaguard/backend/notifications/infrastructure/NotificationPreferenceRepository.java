package com.pesaguard.backend.notifications.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.notifications.domain.NotificationCategory;

public interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreferenceEntity, NotificationPreferenceId> {

    List<NotificationPreferenceEntity> findByUserId(UUID userId);

    List<NotificationPreferenceEntity> findByUserIdAndCategory(UUID userId,
            NotificationCategory category);
}