package com.pesaguard.backend.notifications.application;

import java.util.List;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.notifications.domain.NotificationType;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.rbac.domain.ProductionAccessRequest;

/** Sends production-access decisions only to the requestor or tenant reviewers. */
@Component
public class ProductionAccessNotificationEmitter {

    private final NotificationService notifications;
    private final UserAccountRepository users;
    private final OrganizationMembershipRepository memberships;

    public ProductionAccessNotificationEmitter(NotificationService notifications,
            UserAccountRepository users, OrganizationMembershipRepository memberships) {
        this.notifications = notifications;
        this.users = users;
        this.memberships = memberships;
    }

    public void requestSubmitted(ProductionAccessRequest request) {
        String subject = "Production access review requested";
        String body = "A production access request for \"" + safe(request.getApplicationName())
                + "\" is ready for review.";
        memberships.findAllByOrganizationId(request.getOrganizationId()).stream()
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .filter(membership -> membership.getRole().permissions()
                        .contains(Permission.PRODUCTION_ACCESS_REVIEW))
                .map(membership -> membership.getUser().getId())
                .distinct()
                .forEach(userId -> notify(request, userId,
                        NotificationType.PRODUCTION_REQUEST_RECEIVED, subject, body));
    }

    public void reviewStarted(ProductionAccessRequest request) {
        notifyRequester(request, NotificationType.PRODUCTION_REVIEW_STARTED,
                "Production access review started",
                "A reviewer has started reviewing the production access request for \""
                        + safe(request.getApplicationName()) + "\".");
    }

    public void approved(ProductionAccessRequest request) {
        notifyRequester(request, NotificationType.PRODUCTION_APPROVED,
                "Production access approved",
                "The production access request for \"" + safe(request.getApplicationName())
                        + "\" was approved. The grant is not live until activation completes.");
    }

    public void rejected(ProductionAccessRequest request) {
        notifyRequester(request, NotificationType.PRODUCTION_REJECTED,
                "Production access request declined",
                "The production access request for \"" + safe(request.getApplicationName())
                        + "\" was declined. Review the request in your Developer Dashboard.");
    }

    public void activated(ProductionAccessRequest request) {
        notifyRequester(request, NotificationType.PRODUCTION_ACTIVATED,
                "Production access is active",
                "The production access grant for \"" + safe(request.getApplicationName())
                        + "\" is now active until " + request.getExpiresAt() + ".");
    }

    public void reactivated(ProductionAccessRequest request) {
        notifyRequester(request, NotificationType.PRODUCTION_REACTIVATED,
                "Production access reactivated",
                "The production access grant for \"" + safe(request.getApplicationName())
                        + "\" is active again until " + request.getExpiresAt() + ".");
    }

    public void suspended(ProductionAccessRequest request) {
        notifyRequester(request, NotificationType.PRODUCTION_SUSPENDED,
                "Production access suspended",
                "The production access grant for \"" + safe(request.getApplicationName())
                        + "\" was suspended. Contact an organization security administrator.");
    }

    public void revoked(ProductionAccessRequest request) {
        notifyRequester(request, NotificationType.PRODUCTION_REVOKED,
                "Production access revoked",
                "The production access grant for \"" + safe(request.getApplicationName())
                        + "\" was revoked and can no longer be used.");
    }

    private void notifyRequester(ProductionAccessRequest request, NotificationType type,
            String subject, String body) {
        notify(request, request.getRequestedBy(), type, subject, body);
    }

    private void notify(ProductionAccessRequest request, java.util.UUID userId,
            NotificationType type, String subject, String body) {
        users.findById(userId).ifPresent(user -> notifications.notify(
                request.getOrganizationId(), user.getId(), type, subject, body));
    }

    private static String safe(String value) {
        return value == null ? "application" : value.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
}
