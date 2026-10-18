package com.pesaguard.backend.rbac.domain;

/**
 * Lifecycle of a production access request.
 *
 * <p>Approval and activation are deliberately separate. {@link #APPROVED} records
 * that a reviewer said yes; it does not by itself grant traffic. {@link #ACTIVE}
 * is reached only by an explicit activation once provisioning has succeeded, so
 * there is never a window in which a request is approved but the credential it
 * was approved for does not yet work.
 *
 * <p>The distinction exists because the two events genuinely can diverge: a
 * reviewer approves, then provisioning fails. Folding both into one state would
 * report a working production credential that does not exist.
 */
public enum ProductionAccessStatus {

    /** Raised, nobody has picked it up. */
    PENDING,

    /** A reviewer has claimed it and is evaluating. */
    UNDER_REVIEW,

    /** A decision was recorded, but the grant is not yet live. */
    APPROVED,

    /** Provisioned and live. The only state in which traffic is permitted. */
    ACTIVE,

    REJECTED,

    /** Temporarily withdrawn after being live. Resumable by reactivation. */
    SUSPENDED,

    /** Permanently withdrawn. Terminal, and deliberately not reversible. */
    REVOKED,

    EXPIRED,

    CANCELLED;

    /**
     * Whether traffic is permitted in this state.
     *
     * <p>Only {@link #ACTIVE} qualifies. An approved-but-not-activated request
     * grants nothing, which is the entire point of separating the two.
     */
    public boolean permitsTraffic() {
        return this == ACTIVE;
    }

    /** Whether a reviewer may still act on a request in this state. */
    public boolean isReviewable() {
        return this == PENDING || this == UNDER_REVIEW;
    }

    /** Whether the state can still change. */
    public boolean isTerminal() {
        return this == REJECTED || this == REVOKED || this == EXPIRED || this == CANCELLED;
    }
}