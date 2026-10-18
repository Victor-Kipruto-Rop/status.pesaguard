package com.pesaguard.backend.notifications.application;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.notifications.domain.Notification;
import com.pesaguard.backend.notifications.domain.NotificationChannel;

/**
 * In-app transport: the durable record the portal displays.
 *
 * <p>Always succeeds, because "delivered" here means "stored for the user to
 * read", and the notification itself is the store. This is why in-app cannot be
 * disabled: it is the one channel that cannot fail quietly.
 */
@Component
public class InAppNotificationTransport implements NotificationTransport {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public DeliveryAttemptResult send(Notification notification, String recipient) {
        return DeliveryAttemptResult.delivered();
    }
}