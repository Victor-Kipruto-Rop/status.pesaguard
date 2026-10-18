package com.pesaguard.backend.notifications.domain;

/**
 * How a notification reaches a user.
 */
public enum NotificationChannel {

    /** Delivered to the address on the account. */
    EMAIL,

    /** Stored for the portal to display. Always available. */
    IN_APP,

    /** Requires a configured Web Push provider and user subscription. */
    BROWSER_PUSH,

    /** Reserved for mobile push provider integration. */
    MOBILE_PUSH,

    /** Requires an explicitly configured SMS provider and verified number. */
    SMS,

    /** Uses an explicitly configured notification webhook destination. */
    WEBHOOK;

    /**
     * Whether this channel can be switched off.
     *
     * <p>In-app cannot: it is the durable record, and a notification nobody can
     * read again is a notification that did not happen.
     */
    public boolean isMandatory() {
        return this == IN_APP;
    }

    public boolean isAvailableWithoutProvider() {
        return this == IN_APP || this == EMAIL;
    }
}