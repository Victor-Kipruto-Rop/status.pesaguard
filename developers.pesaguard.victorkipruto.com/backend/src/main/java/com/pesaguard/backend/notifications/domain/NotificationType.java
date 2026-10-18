package com.pesaguard.backend.notifications.domain;

/**
 * Every developer-platform notification.
 *
 * <p>Platform-side operational mail is explicitly out of scope: this catalog
 * covers only what a developer needs to be told about their own integration.
 *
 * <p><b>Mandatory</b> marks an event that reaches the user by email regardless
 * of their stated preferences. A user who disables every email and then has their
 * production key revoked, or who never learns their account showed signs of
 * compromise, has been left worse off by their own preference. Rather than
 * silently overriding a stated choice, the preference is <b>rejected</b> at the
 * point it would create that unsafe state.
 */
public enum NotificationType {

    // Credential lifecycle
    API_KEY_CREATED(NotificationCategory.CREDENTIAL, false),
    API_KEY_ROTATED(NotificationCategory.CREDENTIAL, false),

    /**
     * Mandatory: the credential the user depends on has stopped working. A
     * developer who is not told will discover it from a failed production
     * payment rather than from us.
     */
    API_KEY_REVOKED(NotificationCategory.CREDENTIAL, true),
    CREDENTIAL_EXPIRING(NotificationCategory.CREDENTIAL, false),

    // Webhook delivery
    WEBHOOK_ENDPOINT_FAILING(NotificationCategory.WEBHOOK, false),
    WEBHOOK_REPEATED_DELIVERY_FAILURE(NotificationCategory.WEBHOOK, false),
    WEBHOOK_ENDPOINT_DISABLED(NotificationCategory.WEBHOOK, false),

    // Usage and quota
    QUOTA_WARNING(NotificationCategory.USAGE, false),
    QUOTA_EXCEEDED(NotificationCategory.USAGE, false),
    TRAFFIC_SPIKE(NotificationCategory.USAGE, false),

    // Production access
    PRODUCTION_REQUEST_RECEIVED(NotificationCategory.PRODUCTION, false),
    PRODUCTION_REVIEW_STARTED(NotificationCategory.PRODUCTION, false),
    PRODUCTION_APPROVED(NotificationCategory.PRODUCTION, false),
    PRODUCTION_REJECTED(NotificationCategory.PRODUCTION, false),
    PRODUCTION_ACTIVATED(NotificationCategory.PRODUCTION, false),
    PRODUCTION_REACTIVATED(NotificationCategory.PRODUCTION, false),

    /**
     * Mandatory: a live production grant has been withdrawn. The integration is
     * stopped, and the developer must know why.
     */
    PRODUCTION_SUSPENDED(NotificationCategory.PRODUCTION, true),
    PRODUCTION_REVOKED(NotificationCategory.PRODUCTION, true),

    SUPPORT_TICKET_CREATED(NotificationCategory.SUPPORT, false),
    SUPPORT_TICKET_RESOLVED(NotificationCategory.SUPPORT, true),

    // Security
    SUSPICIOUS_ACTIVITY(NotificationCategory.SECURITY, true),
    CREDENTIAL_COMPROMISE(NotificationCategory.SECURITY, true),
    SESSION_REVOCATION(NotificationCategory.SECURITY, true);

    private final NotificationCategory category;
    private final boolean mandatory;

    NotificationType(NotificationCategory category, boolean mandatory) {
        this.category = category;
        this.mandatory = mandatory;
    }

    public NotificationCategory category() {
        return category;
    }

    /**
     * Whether this event must reach the user by email regardless of preference.
     */
    public boolean mandatory() {
        return mandatory;
    }

    /** Whether a failure sending this event is worth retrying. */
    public boolean isRetryable() {
        // Security and credential events are the ones where a transient network
        // fault must not lose the message. A quota warning can wait for the next
        // cycle without risk.
        return mandatory || category == NotificationCategory.SECURITY;
    }
}
