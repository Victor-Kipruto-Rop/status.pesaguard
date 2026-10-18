package com.pesaguard.backend.audit.domain;

/**
 * The classes of change an audit log must be able to answer questions about.
 *
 * <p>Declared rather than inferred from free-text action strings. An audit trail
 * whose vocabulary drifts is an audit trail nobody can query: {@code
 * "production_acess.requested"} and {@code "production_access.requested"} are
 * different strings, so a query for every rejected production request silently
 * misses one of them.
 *
 * <p>A declared catalog makes that class of mistake impossible to express, and
 * makes coverage of the required event classes provable rather than aspirational.
 */
public enum AuditCategory {

    ORGANIZATION("Organization"),
    MEMBERSHIP("OrganizationMembership"),
    PROJECT("Project"),
    ENVIRONMENT("ProjectEnvironment"),
    ROLE("OrganizationRoleAssignment"),
    PERMISSION("PermissionAssignment"),

    CREDENTIAL("ApiKey"),
    OAUTH_APPLICATION("OAuthApplication"),
    WEBHOOK("WebhookEndpoint"),
    SCOPE("ApiScope"),

    PRODUCTION_ACCESS("ProductionAccessRequest"),
    SECURITY("SecurityAction");

    private final String defaultResourceType;

    AuditCategory(String defaultResourceType) {
        this.defaultResourceType = defaultResourceType;
    }

    /**
     * The resource type events in this category usually concern.
     *
     * <p>A default rather than a rule: a membership removal concerns the
     * membership, not the organization, so the caller overrides it.
     */
    public String defaultResourceType() {
        return defaultResourceType;
    }
}