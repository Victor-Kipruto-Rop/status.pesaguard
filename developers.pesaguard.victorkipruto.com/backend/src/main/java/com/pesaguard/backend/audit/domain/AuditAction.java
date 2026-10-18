package com.pesaguard.backend.audit.domain;

/**
 * The audit vocabulary.
 *
 * <p>Every auditable action the platform records. The stored {@code action} string
 * is the wire form of one of these values, so a typo cannot be recorded by
 * accident and a query over an action is exhaustive by construction.
 *
 * <p>Three properties are enforced by construction rather than by convention:
 *
 * <ul>
 *   <li>the action string is derived, never supplied, so it cannot be misspelled;</li>
 *   <li>the category is carried alongside, so coverage of the required event
 *       classes is provable with a test rather than asserted in a document;</li>
 *   <li>the resource type has a declared default, so an event is not filed against
 *       the wrong kind of resource by accident.</li>
 * </ul>
 */
public enum AuditAction {

    // Organization
    ORGANIZATION_UPDATED(AuditCategory.ORGANIZATION),
    ORGANIZATION_RENAMED(AuditCategory.ORGANIZATION),

    // Membership
    MEMBER_INVITED(AuditCategory.MEMBERSHIP),
    MEMBER_JOINED(AuditCategory.MEMBERSHIP),
    MEMBER_REMOVED(AuditCategory.MEMBERSHIP),
    MEMBERSHIP_SUSPENDED(AuditCategory.MEMBERSHIP),
    MEMBERSHIP_REINSTATED(AuditCategory.MEMBERSHIP),

    // Project
    PROJECT_CREATED(AuditCategory.PROJECT),
    PROJECT_UPDATED(AuditCategory.PROJECT),
    PROJECT_ARCHIVED(AuditCategory.PROJECT),
    PROJECT_DELETED(AuditCategory.PROJECT),

    // Environment
    ENVIRONMENT_CREATED(AuditCategory.ENVIRONMENT),
    ENVIRONMENT_UPDATED(AuditCategory.ENVIRONMENT),
    ENVIRONMENT_PROMOTED(AuditCategory.ENVIRONMENT),
    ENVIRONMENT_ARCHIVED(AuditCategory.ENVIRONMENT),

    // Role and permission
    ROLE_ASSIGNED(AuditCategory.ROLE),
    ROLE_REVOKED(AuditCategory.ROLE),
    PERMISSION_GRANTED(AuditCategory.PERMISSION),
    PERMISSION_REVOKED(AuditCategory.PERMISSION),

    // API keys
    API_KEY_CREATED(AuditCategory.CREDENTIAL),
    API_KEY_ROTATED(AuditCategory.CREDENTIAL),
    API_KEY_REVOKED(AuditCategory.CREDENTIAL),
    API_KEY_COMPROMISED(AuditCategory.CREDENTIAL),
    API_KEY_SUSPENDED(AuditCategory.CREDENTIAL),
    API_KEY_RESTRICTED(AuditCategory.CREDENTIAL),

    // OAuth
    OAUTH_APPLICATION_CREATED(AuditCategory.OAUTH_APPLICATION),
    OAUTH_APPLICATION_UPDATED(AuditCategory.OAUTH_APPLICATION),
    OAUTH_APPLICATION_SUSPENDED(AuditCategory.OAUTH_APPLICATION),
    OAUTH_APPLICATION_REVOKED(AuditCategory.OAUTH_APPLICATION),
    OAUTH_TOKEN_ISSUED(AuditCategory.OAUTH_APPLICATION),
    OAUTH_TOKEN_REVOKED(AuditCategory.OAUTH_APPLICATION),

    // Webhooks
    WEBHOOK_ENDPOINT_CREATED(AuditCategory.WEBHOOK),
    WEBHOOK_ENDPOINT_UPDATED(AuditCategory.WEBHOOK),
    WEBHOOK_ENDPOINT_DISABLED(AuditCategory.WEBHOOK),
    WEBHOOK_SECRET_ROTATED(AuditCategory.WEBHOOK),

    // Scopes
    SCOPE_ASSIGNED(AuditCategory.SCOPE),
    SCOPE_REVOKED(AuditCategory.SCOPE),
    SCOPE_DEPRECATED(AuditCategory.SCOPE),
    SCOPE_RESTRICTED(AuditCategory.SCOPE),

    // Production access
    PRODUCTION_ACCESS_REQUESTED(AuditCategory.PRODUCTION_ACCESS),
    PRODUCTION_REVIEW_STARTED(AuditCategory.PRODUCTION_ACCESS),
    PRODUCTION_APPROVED(AuditCategory.PRODUCTION_ACCESS),
    PRODUCTION_ACTIVATED(AuditCategory.PRODUCTION_ACCESS),
    PRODUCTION_REJECTED(AuditCategory.PRODUCTION_ACCESS),
    PRODUCTION_SUSPENDED(AuditCategory.PRODUCTION_ACCESS),
    PRODUCTION_REVOKED(AuditCategory.PRODUCTION_ACCESS),

    // Security
    SECURITY_EVENT_RECORDED(AuditCategory.SECURITY),
    CREDENTIAL_COMPROMISE_CONFIRMED(AuditCategory.SECURITY),
    SESSION_REVOKED(AuditCategory.SECURITY),
    EMERGENCY_REVOCATION(AuditCategory.SECURITY),
    SECURITY_EVENT_RESOLVED(AuditCategory.SECURITY);

    private final AuditCategory category;

    AuditAction(AuditCategory category) {
        this.category = category;
    }

    public AuditCategory category() {
        return category;
    }

    /**
     * The stored action string.
     *
     * <p>Lower snake case, matching the convention the codebase already writes by
     * hand elsewhere. Derived rather than passed in, so it cannot drift from the
     * constant or be misspelled at a call site.
     */
    public String value() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The resource type this action usually concerns. */
    public String defaultResourceType() {
        return category.defaultResourceType();
    }

    /**
     * Whether this action changes a permission boundary.
     *
     * <p>Used to mark the events worth alerting on. Derived from the category so a
     * new action cannot silently fall outside the boundary-change set.
     */
    public boolean isSecuritySensitive() {
        return category == AuditCategory.ROLE
                || category == AuditCategory.PERMISSION
                || category == AuditCategory.SECURITY
                || category == AuditCategory.CREDENTIAL;
    }

    /**
     * Parses a stored action string.
     *
     * <p>Used when reading historical rows, which may predate the catalog. A value
     * that no longer matches returns empty rather than throwing: audit history must
     * remain readable after the vocabulary changes, and a row that cannot be
     * classified is still a row an investigator needs to see.
     */
    public static java.util.Optional<AuditAction> parse(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        String candidate = value.trim();
        for (AuditAction action : values()) {
            if (action.value().equals(candidate)) {
                return java.util.Optional.of(action);
            }
        }
        return java.util.Optional.empty();
    }

    public static java.util.List<String> allValues() {
        return java.util.Arrays.stream(values()).map(AuditAction::value).sorted().toList();
    }
}