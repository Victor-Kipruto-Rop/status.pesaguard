package com.pesaguard.backend.notifications.application;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.member.application.AccountEmailDeliveryService;
import com.pesaguard.backend.notifications.domain.Notification;
import com.pesaguard.backend.notifications.domain.NotificationChannel;

/** Delivers branded notification email through the configured email provider. */
@Component
public class EmailNotificationTransport implements NotificationTransport {

    private final AccountEmailDeliveryService emailDelivery;

    public EmailNotificationTransport(AccountEmailDeliveryService emailDelivery) {
        this.emailDelivery = emailDelivery;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public DeliveryAttemptResult send(Notification notification, String recipient) {
        if (!validAddress(recipient)) {
            return DeliveryAttemptResult.permanentFailure("invalid address");
        }
        if (containsLineBreak(notification.subject())) {
            return DeliveryAttemptResult.permanentFailure("invalid subject");
        }

        return emailDelivery.sendNotification(recipient.trim(), notification.subject(),
                notification.body(), notification.id().toString())
                ? DeliveryAttemptResult.accepted()
                : DeliveryAttemptResult.transientFailure("email delivery failed");
    }

    private static boolean validAddress(String address) {
        if (address == null || address.isBlank() || containsLineBreak(address)) {
            return false;
        }
        int at = address.indexOf('@');
        return at > 0 && at == address.lastIndexOf('@') && at < address.length() - 1
                && address.indexOf(' ') < 0;
    }

    private static boolean containsLineBreak(String value) {
        return value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0);
    }
}
