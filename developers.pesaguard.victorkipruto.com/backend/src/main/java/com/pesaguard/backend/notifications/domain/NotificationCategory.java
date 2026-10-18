package com.pesaguard.backend.notifications.domain;

/**
 * Notification categories, which are also the unit of user preference.
 *
 * <p>Preferences are set per category rather than per notification, because
 * requiring a decision on each of twenty individual events is how preference
 * systems end up ignored.
 */
public enum NotificationCategory {

    ACCOUNT,
    ORGANIZATION,
    PROJECT,
    ENVIRONMENT,
    CREDENTIAL,
    API_KEY,
    API_USAGE,
    WEBHOOK,
    INTEGRATION,
    TEAM,
    BILLING,
    SYSTEM,
    DEVELOPER,
    PLATFORM,
    USAGE,
    PRODUCTION,
    SUPPORT,

    /**
     * Security signals about the account itself.
     *
     * <p>Separate from CREDENTIAL even though both concern credentials: a
     * credential lifecycle change is routine administration, while a compromise
     * signal means someone else may be acting as the customer.
     */
    SECURITY;

    /**
     * Whether a category contains at least one event that must reach the user by
     * email whatever their stated preferences.
     *
     * <p>Drives whether email may be switched off for this category. See
     * {@link NotificationType#mandatory()} for which events qualify and why.
     */
    public boolean allowsDisablingEmail() {
        return !hasMandatoryEvents();
    }

    /**
     * Whether any event in this category is safety-critical.
     *
     * <p>Computed from the catalog rather than declared, so adding a mandatory
     * event cannot be forgotten here.
     */
    public boolean hasMandatoryEvents() {
        for (NotificationType type : NotificationType.values()) {
            if (type.category() == this && type.mandatory()) {
                return true;
            }
        }
        return false;
    }
}