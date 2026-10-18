package com.pesaguard.backend.platformadmin.domain;

/**
 * Capabilities an internal operator may hold.
 *
 * <p><b>A namespace entirely separate from {@code Permission}.</b> Tenant
 * permissions are grantable by an organization owner to its own members;
 * operator capabilities are not, and cannot be, because they are not held by a
 * tenant at all. Reusing the tenant catalog would mean an owner could grant
 * themselves {@code platform.credentials.revoke} over every other tenant.
 *
 * <p>Read capabilities are separated from mutating ones so that the common case
 * — an operator answering a support question — does not require the ability to
 * revoke a customer's live credential.
 */
public enum OperatorCapability {

    /** See organizations across tenants. */
    ORGANIZATIONS_READ,

    /** See projects and environments across tenants. */
    PROJECTS_READ,

    /**
     * Inspect credentials.
     *
     * <p>Read-only and explicitly not the secret. An operator examining a
     * credential sees its prefix, scopes, and usage, never the value.
     */
    CREDENTIALS_READ,

    /** Suspend a credential, reversibly. */
    CREDENTIALS_SUSPEND,

    /** Revoke a credential. Terminal. */
    CREDENTIALS_REVOKE,

    /** See webhook endpoint health. */
    WEBHOOKS_READ,

    /** See usage. */
    USAGE_READ,

    /** Access support tickets across organizations. */
    SUPPORT_READ,

    /** Resolve support tickets across organizations. */
    SUPPORT_RESOLVE,

    /** See production access requests across tenants. */
    PRODUCTION_READ,

    /**
     * Review developer verification.
     *
     * <p>Separate from merely reading a request: reviewing is a decision, and
     * granting it alongside read means every read-only operator can approve a
     * production grant.
     */
    PRODUCTION_REVIEW,

    /** See security signals. */
    SECURITY_READ,

    /**
     * Resolve a security signal.
     *
     * <p>Separate from reading for the same reason Phase 15 kept resolution
     * distinct from detection: a detector must never be able to close its own
     * findings, and neither should a read-only operator.
     */
    SECURITY_RESOLVE,

    /** Read and change developer-platform configuration. */
    PLATFORM_CONFIG_READ,
    PLATFORM_CONFIG_WRITE,

    /** Read billing records and payment processing state across organizations. */
    BILLING_READ,

    /** Issue, void, and reconcile customer invoices or payments. */
    BILLING_WRITE;

    /**
     * Whether this capability changes customer-visible state.
     *
     * <p>Used to require a stated reason on every mutating action. A revoke
     * performed without one is indistinguishable from a mistake after the fact.
     */
    public boolean isMutating() {
        return this == CREDENTIALS_SUSPEND || this == CREDENTIALS_REVOKE
                || this == PRODUCTION_REVIEW || this == SECURITY_RESOLVE
                || this == SUPPORT_RESOLVE || this == PLATFORM_CONFIG_WRITE || this == BILLING_WRITE;
    }

    /** Whether this capability concerns credentials. */
    public boolean concernsCredentials() {
        return this == CREDENTIALS_READ || this == CREDENTIALS_SUSPEND
                || this == CREDENTIALS_REVOKE;
    }

    /** Whether this capability is read-only. */
    public boolean isReadOnly() {
        return !isMutating();
    }
}