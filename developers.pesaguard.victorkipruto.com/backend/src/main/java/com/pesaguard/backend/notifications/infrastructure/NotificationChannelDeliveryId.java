package com.pesaguard.backend.notifications.infrastructure;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import com.pesaguard.backend.notifications.domain.NotificationChannel;

/** Composite identity for a notification's channel state row. */
public class NotificationChannelDeliveryId implements Serializable {

    private UUID notificationId;
    private NotificationChannel channel;

    public NotificationChannelDeliveryId() {}

    public NotificationChannelDeliveryId(UUID notificationId, NotificationChannel channel) {
        this.notificationId = notificationId;
        this.channel = channel;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof NotificationChannelDeliveryId that)) return false;
        return Objects.equals(notificationId, that.notificationId) && channel == that.channel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(notificationId, channel);
    }
}
