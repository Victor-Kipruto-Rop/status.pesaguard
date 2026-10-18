package com.pesaguard.backend.securitycenter.application;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.pesaguard.backend.credentials.api.ApiKeyRepository;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.notifications.application.NotificationService;
import com.pesaguard.backend.notifications.domain.NotificationType;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.securitycenter.domain.SecurityEvent;
import com.pesaguard.backend.securitycenter.domain.SecurityEventType;

/** Routes security alerts only to the affected user and active tenant security readers. */
@Component
public class SecurityEventNotificationEmitter {

    private final NotificationService notifications;
    private final UserAccountRepository users;
    private final ApiKeyRepository apiKeys;
    private final OrganizationMembershipRepository memberships;

    public SecurityEventNotificationEmitter(NotificationService notifications,
            UserAccountRepository users, ApiKeyRepository apiKeys,
            OrganizationMembershipRepository memberships) {
        this.notifications = notifications;
        this.users = users;
        this.apiKeys = apiKeys;
        this.memberships = memberships;
    }

    public void notify(SecurityEvent.Record event) {
        NotificationType type = notificationType(event.type());
        String message = message(event.type());
        Set<UUID> recipients = new LinkedHashSet<>();
        if ("user".equalsIgnoreCase(event.subjectKind()) && event.subjectId() != null) {
            recipients.add(event.subjectId());
        } else if ("api_key".equalsIgnoreCase(event.subjectKind()) && event.subjectId() != null) {
            apiKeys.findByIdAndOrganizationId(event.subjectId(), event.organizationId())
                    .map(com.pesaguard.backend.credentials.api.ApiKey::getCreatedBy)
                    .ifPresent(recipients::add);
        }
        memberships.findAllByOrganizationId(event.organizationId()).stream()
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .filter(membership -> membership.getRole().permissions().contains(Permission.SECURITY_READ))
                .map(membership -> membership.getUser().getId())
                .forEach(recipients::add);
        for (UUID userId : recipients) {
            users.findById(userId).ifPresent(user -> notifications.notify(
                    event.organizationId(), user.getId(), type, "Security activity detected",
                    message));
        }
    }

    private static NotificationType notificationType(SecurityEventType type) {
        return switch (type) {
            case REVOKED_CREDENTIAL_USAGE -> NotificationType.CREDENTIAL_COMPROMISE;
            default -> NotificationType.SUSPICIOUS_ACTIVITY;
        };
    }

    private static String message(SecurityEventType type) {
        return switch (type) {
            case REVOKED_CREDENTIAL_USAGE -> "A revoked API credential was presented. Check the key's integrations and revoke any related credentials you no longer trust.";
            case ALLOWLIST_VIOLATION -> "An API credential request came from outside its configured IP allowlist.";
            case ABNORMAL_API_USAGE -> "API traffic differed significantly from this credential's normal usage.";
            case TOKEN_REPLAY -> "A previously used authentication token was presented again.";
            case REPEATED_FAILURES -> "Repeated authentication or authorization failures were detected.";
            case SUSPICIOUS_WEBHOOK_ACTIVITY -> "Unusual activity involving a webhook endpoint was detected.";
            case UNFAMILIAR_DEVICE_SIGNIN -> "A sign-in came from a device family not previously used by this account.";
            case SCOPE_ABUSE -> "An API credential attempted to use a scope it was not granted.";
            case AUTHORIZATION_FAILURE -> "An access attempt crossed a tenant or resource authorization boundary and was denied.";
        };
    }
}
