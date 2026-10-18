package com.pesaguard.backend.notifications.application;

import java.util.LinkedHashSet;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.notifications.domain.NotificationType;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.rbac.domain.Permission;

/** Alerts active webhook administrators when a delivery exhausts its retry budget. */
@Component
public class WebhookDeliveryNotificationEmitter {

    private final NotificationService notifications;
    private final UserAccountRepository users;
    private final OrganizationMembershipRepository memberships;

    public WebhookDeliveryNotificationEmitter(NotificationService notifications,
            UserAccountRepository users, OrganizationMembershipRepository memberships) {
        this.notifications = notifications;
        this.users = users;
        this.memberships = memberships;
    }

    public void deadLettered(java.util.UUID organizationId, String eventType,
            String endpointId, int attempts) {
        var recipients = new LinkedHashSet<java.util.UUID>();
        memberships.findAllByOrganizationId(organizationId).stream()
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .filter(membership -> membership.getRole().permissions().contains(Permission.WEBHOOK_READ))
                .map(membership -> membership.getUser().getId())
                .forEach(recipients::add);
        String subject = "Webhook delivery needs attention";
        String body = "An event delivery to endpoint " + safe(endpointId) + " failed after " + attempts
                + " attempts. Event type: " + safe(eventType)
                + ". Review the delivery history and replay it after resolving the endpoint issue.";
        recipients.forEach(userId -> users.findById(userId).ifPresent(user -> notifications.notify(
                organizationId, user.getId(), NotificationType.WEBHOOK_REPEATED_DELIVERY_FAILURE,
                subject, body)));
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "?");
    }
}
