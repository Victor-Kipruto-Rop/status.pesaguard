package com.pesaguard.backend.member.domain;

/**
 * Developer account lifecycle.
 *
 * <p>{@link #DELETED} is distinct from {@link #DEACTIVATED} because the two are
 * not the same promise. Deactivation says "this account is not in use right now";
 * deletion says "this developer asked to be removed and a grace period has
 * elapsed". Only the second is terminal.
 *
 * <p>There is deliberately no path from {@link #DELETED} back to
 * {@link #ACTIVE}. Reinstating a deleted account would resurrect a developer who
 * asked to be forgotten, which is a privacy expectation and not merely a
 * preference.
 */
public enum UserStatus {

    /** Registered and usable. May or may not have a verified email. */
    ACTIVE,

    /** Temporarily blocked. Restorable. */
    SUSPENDED,

    /** Not in use by choice. Restorable. */
    DEACTIVATED,

    /**
     * Removal requested. The account still exists and can still sign in, so a
     * mistaken deletion is recoverable during the grace period.
     */
    PENDING_DELETION,

    /** Removal completed. Terminal. */
    DELETED;

    public boolean isTerminal() {
        return this == DELETED;
    }

    /** Whether a fresh login may be established for this account. */
    public boolean canAuthenticate() {
        return this == ACTIVE || this == PENDING_DELETION;
    }

    /**
     * Whether the account is inside its deletion grace period.
     *
     * <p>A suspended account must still be able to sign in to complete a
     * deletion, or a developer suspended for abuse could never remove themselves.
     */
    public boolean canCompleteDeletion() {
        return this == PENDING_DELETION || this == SUSPENDED;
    }
}