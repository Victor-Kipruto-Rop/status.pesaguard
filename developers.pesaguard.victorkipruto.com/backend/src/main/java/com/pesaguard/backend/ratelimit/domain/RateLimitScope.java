package com.pesaguard.backend.ratelimit.domain;

/**
 * Which counter a limit applies to.
 *
 * <p>Scopes compose: one request is evaluated against every scope it belongs to
 * and is allowed only if all of them allow it. That is how a single noisy API
 * key is stopped without throttling the whole organization it belongs to.
 */
public enum RateLimitScope {
    API_KEY,
    PROJECT,
    ENVIRONMENT,
    ORGANIZATION,
    ENDPOINT,
    IP,
    USER,
    WEBHOOK;

    /**
     * Whether the scope identifies a tenant-owned resource.
     *
     * <p>Used by the key builder to decide if the counter key needs the
     * organization prefix. A key must be globally unique: without the
     * organization, two tenants whose API key ids somehow collided would share a
     * counter, and one tenant could exhaust another's limit.
     */
    public boolean isTenantScoped() {
        return this == API_KEY || this == PROJECT || this == ENVIRONMENT
                || this == ENDPOINT || this == WEBHOOK;
    }

    /** Scopes keyed on something outside the tenant, such as a network address. */
    public boolean isShared() {
        return this == IP;
    }
}