package com.pesaguard.backend.notifications.application;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.analytics.infrastructure.UsageQuotaAlertStore;
import com.pesaguard.backend.environment.infrastructure.EnvironmentLimitsRepository;
import com.pesaguard.backend.member.infrastructure.UserAccountRepository;
import com.pesaguard.backend.notifications.domain.NotificationType;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.rbac.domain.Permission;

/** Applies environment request-rate thresholds and emits deduplicated tenant alerts. */
@Service
public class UsageQuotaAlertService {

    private static final double WARNING_THRESHOLD = 0.8d;

    private final EnvironmentLimitsRepository limitsRepository;
    private final UsageQuotaAlertStore alertStore;
    private final OrganizationMembershipRepository memberships;
    private final UserAccountRepository users;
    private final NotificationService notifications;

    public UsageQuotaAlertService(EnvironmentLimitsRepository limitsRepository,
            UsageQuotaAlertStore alertStore, OrganizationMembershipRepository memberships,
            UserAccountRepository users, NotificationService notifications) {
        this.limitsRepository = limitsRepository;
        this.alertStore = alertStore;
        this.memberships = memberships;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional
    public void evaluate(UUID organizationId, UUID environmentId, long requestCount,
            Instant now) {
        var limits = limitsRepository.findByEnvironmentId(environmentId)
                .filter(existing -> existing.getOrganizationId().equals(organizationId))
                .orElse(null);
        if (limits == null || limits.getRequestsPerMinute() < 1) return;

        boolean exceeded = requestCount >= limits.getRequestsPerMinute();
        boolean warning = requestCount >= Math.ceil(limits.getRequestsPerMinute() * WARNING_THRESHOLD);
        if (!warning) return;

        String type = exceeded ? "QUOTA_EXCEEDED" : "QUOTA_WARNING";
        Instant periodStart = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        if (!alertStore.claim(environmentId, organizationId, type, periodStart)) return;

        NotificationType notificationType = exceeded
                ? NotificationType.QUOTA_EXCEEDED : NotificationType.QUOTA_WARNING;
        String subject = exceeded ? "Environment request limit reached" : "Environment nearing request limit";
        String body = exceeded
                ? "Environment " + environmentId + " handled " + requestCount
                        + " requests in the last minute, reaching its configured limit of "
                        + limits.getRequestsPerMinute() + ". Requests may be rate limited."
                : "Environment " + environmentId + " handled " + requestCount
                        + " requests in the last minute, at least 80% of its configured limit of "
                        + limits.getRequestsPerMinute() + ".";
        var recipients = new LinkedHashSet<UUID>();
        memberships.findAllByOrganizationId(organizationId).stream()
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .filter(membership -> membership.getRole().permissions().contains(Permission.USAGE_READ))
                .map(membership -> membership.getUser().getId())
                .forEach(recipients::add);
        recipients.forEach(userId -> users.findById(userId).ifPresent(user -> notifications.notify(
                organizationId, user.getId(), notificationType, subject, body)));
    }
}
