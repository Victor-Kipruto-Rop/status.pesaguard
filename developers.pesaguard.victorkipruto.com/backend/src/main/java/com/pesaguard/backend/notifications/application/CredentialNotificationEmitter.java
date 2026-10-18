package com.pesaguard.backend.notifications.application;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.credentials.api.ApiKey;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.notifications.domain.NotificationType;

/** Raises durable credential notifications without ever including secret material. */
@Component
public class CredentialNotificationEmitter {

    private final NotificationService notifications;
    private final UserAccountRepository users;

    public CredentialNotificationEmitter(NotificationService notifications, UserAccountRepository users) {
        this.notifications = notifications;
        this.users = users;
    }

    public void apiKeyCreated(ApiKey key) {
        notify(key, NotificationType.API_KEY_CREATED, "API key created",
                "The API key \"" + safeName(key.getName()) + "\" was created. Its secret is shown only once.");
    }

    public void apiKeyRevoked(ApiKey key, String reason) {
        String detail = "compromised".equals(reason)
                ? "It was marked compromised and can no longer authenticate."
                : "It was revoked and can no longer authenticate.";
        notify(key, NotificationType.API_KEY_REVOKED, "API key revoked",
                "The API key \"" + safeName(key.getName()) + "\" " + detail);
    }

    public void apiKeyRotated(ApiKey key) {
        notify(key, NotificationType.API_KEY_ROTATED, "API key rotated",
                "The API key \"" + safeName(key.getName())
                        + "\" was rotated. The previous secret has been revoked.");
    }

    private void notify(ApiKey key, NotificationType type, String subject, String body) {
        users.findById(key.getCreatedBy()).ifPresent(user -> notifications.notify(
                key.getOrganizationId(), user.getId(), type, subject, body));
    }

    private static String safeName(String value) {
        return value == null ? "unnamed" : value.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
}
