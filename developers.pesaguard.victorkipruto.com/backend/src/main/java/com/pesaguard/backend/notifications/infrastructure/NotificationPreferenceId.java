package com.pesaguard.backend.notifications.infrastructure;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import com.pesaguard.backend.notifications.domain.NotificationCategory;

/** Composite key for {@link NotificationPreferenceEntity}. */
public class NotificationPreferenceId implements Serializable {

    private UUID userId;
    private NotificationCategory category;

    public NotificationPreferenceId() {
    }

    public NotificationPreferenceId(UUID userId, NotificationCategory category) {
        this.userId = userId;
        this.category = category;
    }

    public UUID getUserId() { return userId; }
    public NotificationCategory getCategory() { return category; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NotificationPreferenceId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && category == that.category;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, category);
    }
}