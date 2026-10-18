package com.pesaguard.backend.rbac.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The permission catalog. Permissions are the single source of truth for what an
 * actor may do; roles are only bundles of these values.
 *
 * <p>Permission strings are matched in canonical lowercase form only. A
 * differently cased value is rejected rather than normalized, so what is stored
 * and audited is always exactly what the catalog defines.
 */
public enum Permission {
    ORGANIZATION_READ("organization:read"),
    ORGANIZATION_UPDATE("organization:update"),

    PROJECT_READ("project:read"),
    PROJECT_CREATE("project:create"),
    PROJECT_UPDATE("project:update"),
    PROJECT_DELETE("project:delete"),

    ENVIRONMENT_READ("environment:read"),
    ENVIRONMENT_CREATE("environment:create"),
    ENVIRONMENT_UPDATE("environment:update"),

    CREDENTIAL_READ("credential:read"),
    CREDENTIAL_CREATE("credential:create"),
    CREDENTIAL_REVOKE("credential:revoke"),
    CREDENTIAL_ROTATE("credential:rotate"),
    // Editing credential metadata and the scope registry. Separate from rotate
    // and revoke: annotating a scope must not imply the power to mint a new
    // secret or destroy one.
    CREDENTIAL_UPDATE("credential:update"),

    WEBHOOK_READ("webhook:read"),
    WEBHOOK_CREATE("webhook:create"),
    WEBHOOK_UPDATE("webhook:update"),
    WEBHOOK_DELETE("webhook:delete"),

    OAUTH_APPLICATION_READ("oauth_application:read"),
    OAUTH_APPLICATION_CREATE("oauth_application:create"),
    OAUTH_APPLICATION_UPDATE("oauth_application:update"),
    OAUTH_APPLICATION_ROTATE("oauth_application:rotate"),
    OAUTH_APPLICATION_SUSPEND("oauth_application:suspend"),
    OAUTH_APPLICATION_REVOKE("oauth_application:revoke"),

    SANDBOX_READ("sandbox:read"),
    SANDBOX_CREATE("sandbox:create"),
    SANDBOX_UPDATE("sandbox:update"),
    SANDBOX_ACTIVATE("sandbox:activate"),
    SANDBOX_SUSPEND("sandbox:suspend"),
    SANDBOX_RESET("sandbox:reset"),
    SANDBOX_EXECUTE("sandbox:execute"),
    SANDBOX_DELETE("sandbox:delete"),

    USAGE_READ("usage:read"),

    AUDIT_READ("audit:read"),

    // Reading one organization's own security posture, and terminating its
    // credentials and sessions in an incident. Separate from AUDIT_READ: a viewer
    // who may read the audit log has no reason to be able to revoke production
    // credentials, and bundling them would grant that by default.
    SECURITY_READ("security:read"),
    SECURITY_CONTROL("security:control"),

    PRODUCTION_ACCESS_REQUEST("production_access:request"),
    PRODUCTION_ACCESS_REVIEW("production_access:review"),
    PRODUCTION_VIEW("production:view"),
    PRODUCTION_VERIFY("production:verify"),
    PRODUCTION_LAUNCH("production:launch"),
    PRODUCTION_SUSPEND("production:suspend"),
    PRODUCTION_RESUME("production:resume"),

    /**
     * Reading an organization's own settings, without being able to change them.
     *
     * <p>Separate from ORGANIZATION_READ so a workspace's security posture can be
     * shown to someone who is not being trusted to alter it -- the read-only half
     * of an administrator's power.
     */
    WORKSPACE_SETTINGS_READ("workspace_settings:read"),

    /**
     * Changing an organization's settings.
     *
     * <p>Held only by OWNER and ADMIN. It is the power to weaken session lifetime,
     * disable the password login method, or widen IP restrictions, so it is not
     * bundled into the general update permission: renaming a workspace must never
     * imply the ability to relax its security posture.
     */
    WORKSPACE_SETTINGS_UPDATE("workspace_settings:update"),

    /**
     * Inviting people into an organization.
     *
     * <p>Separate from membership administration. Issuing an invitation is how
     * unverified outsiders are brought in, and a role that can remove members but
     * not add them is a deliberately incomplete administration power; this keeps
     * the two separable for teams that split them.
     */
    WORKSPACE_INVITE("workspace:invite"),

    /**
     * Removing or suspending members.
     *
     * <p>Separate from WORKSPACE_INVITE so that inviting and expelling can be
     * granted independently.
     */
    WORKSPACE_MEMBER_REMOVE("workspace_member:remove");

    private final String value;

    Permission(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** The resource a permission acts on, used for grouping and policy checks. */
    public String resource() {
        return value.substring(0, value.indexOf(':'));
    }

    public static Optional<Permission> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String candidate = value.trim();
        return Arrays.stream(values())
                .filter(permission -> permission.value.equals(candidate))
                .findFirst();
    }

    public static java.util.List<String> allValues() {
        return Arrays.stream(values()).map(Permission::value).sorted().toList();
    }
}