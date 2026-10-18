package com.pesaguard.backend.notifications.application;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.notifications.domain.NotificationChannel;
import com.pesaguard.backend.notifications.domain.NotificationSeverity;
import com.pesaguard.backend.notifications.domain.NotificationType;

@Component
public class NotificationRuleEngine {

    public NotificationSeverity severityFor(NotificationType type) {
        return switch (type) {
            case SUSPICIOUS_ACTIVITY, CREDENTIAL_COMPROMISE, SESSION_REVOCATION -> NotificationSeverity.SECURITY;
            case API_KEY_REVOKED, PRODUCTION_SUSPENDED, PRODUCTION_REVOKED,
                    SUPPORT_TICKET_RESOLVED -> NotificationSeverity.CRITICAL;
            case QUOTA_EXCEEDED, TRAFFIC_SPIKE, WEBHOOK_REPEATED_DELIVERY_FAILURE,
                    WEBHOOK_ENDPOINT_DISABLED -> NotificationSeverity.ERROR;
            case QUOTA_WARNING, CREDENTIAL_EXPIRING, WEBHOOK_ENDPOINT_FAILING ->
                NotificationSeverity.WARNING;
            case API_KEY_CREATED, API_KEY_ROTATED, PRODUCTION_ACTIVATED,
                    PRODUCTION_REACTIVATED -> NotificationSeverity.SUCCESS;
            case SUPPORT_TICKET_CREATED -> NotificationSeverity.INFO;
            default -> NotificationSeverity.INFO;
        };
    }

    public Set<NotificationChannel> channelsFor(NotificationType type) {
        NotificationSeverity severity = severityFor(type);
        if (type.mandatory() || severity == NotificationSeverity.CRITICAL
                || severity == NotificationSeverity.SECURITY
                || severity == NotificationSeverity.ERROR
                || severity == NotificationSeverity.WARNING) {
            return EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP);
        }
        return EnumSet.of(NotificationChannel.IN_APP);
    }

    public Set<NotificationChannel> channelsFor(NotificationType type,
            Set<NotificationChannel> preferredChannels) {
        return channelsFor(type, preferredChannels,
                EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP));
    }

    public Set<NotificationChannel> channelsFor(NotificationType type,
            Set<NotificationChannel> preferredChannels,
            Set<NotificationChannel> availableChannels) {
        EnumSet<NotificationChannel> selected = EnumSet.copyOf(channelsFor(type));
        for (NotificationChannel channel : preferredChannels) {
            if (availableChannels.contains(channel)) selected.add(channel);
        }
        if (type.mandatory()) selected.add(NotificationChannel.EMAIL);
        selected.add(NotificationChannel.IN_APP);
        return Set.copyOf(selected);
    }
}
