package com.pesaguard.backend.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pesaguard.backend.member.application.AccountEmailDeliveryService;
import com.pesaguard.backend.notifications.domain.DeliveryState;
import com.pesaguard.backend.notifications.domain.Notification;
import com.pesaguard.backend.notifications.domain.NotificationChannel;
import com.pesaguard.backend.notifications.domain.NotificationPreferences;
import com.pesaguard.backend.notifications.domain.NotificationType;

@ExtendWith(MockitoExtension.class)
class EmailNotificationTransportTest {

    @Mock
    private AccountEmailDeliveryService emailDelivery;

    @Test
    void marksProviderAcceptanceAndUsesTheStableNotificationIdAsIdempotencyKey() {
        Notification notification = Notification.addressedTo(UUID.randomUUID(), UUID.randomUUID(),
                NotificationType.API_KEY_REVOKED, "Key revoked", "An API key was revoked.",
                NotificationPreferences.defaultsFor(UUID.randomUUID()), NotificationChannel.EMAIL);
        when(emailDelivery.sendNotification("developer@example.com", notification.subject(),
                notification.body(), notification.id().toString())).thenReturn(true);

        NotificationTransport.DeliveryAttemptResult result =
                new EmailNotificationTransport(emailDelivery).send(notification, "developer@example.com");

        assertThat(result.success()).isTrue();
        assertThat(result.successState()).isEqualTo(DeliveryState.ACCEPTED);
        verify(emailDelivery).sendNotification("developer@example.com", notification.subject(),
                notification.body(), notification.id().toString());
    }

    @Test
    void invalidAddressesArePermanentFailures() {
        Notification notification = Notification.addressedTo(UUID.randomUUID(), UUID.randomUUID(),
                NotificationType.API_KEY_REVOKED, "Key revoked", "An API key was revoked.",
                NotificationPreferences.defaultsFor(UUID.randomUUID()), NotificationChannel.EMAIL);

        NotificationTransport.DeliveryAttemptResult result =
                new EmailNotificationTransport(emailDelivery).send(notification, "bad address");

        assertThat(result.success()).isFalse();
        assertThat(result.isTransient()).isFalse();
    }
}
