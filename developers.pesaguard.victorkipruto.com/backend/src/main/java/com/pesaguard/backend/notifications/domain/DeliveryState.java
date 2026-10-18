package com.pesaguard.backend.notifications.domain;

/**
 * Where one notification stands.
 *
 * <p>Terminal states are terminal. A delivered or permanently-failed
 * notification is never re-sent, because a duplicate "your key was revoked" is
 * noise that trains users to ignore security mail.
 */
public enum DeliveryState {

    /** Accepted, not yet attempted. */
    PENDING,

    /** One attempt is in progress. */
    IN_PROGRESS,

    /** Accepted by the external delivery provider; inbox delivery is unconfirmed. */
    ACCEPTED,

    /** Handed to the channel successfully. */
    DELIVERED,

    /**
     * An attempt failed but retries remain.
     *
     * <p>Distinct from FAILED so a transient network fault is not recorded as a
     * permanent loss, and so the queue can pick it up again.
     */
    RETRY_SCHEDULED,

    /**
     * Exhausted its attempts, or failed unrecoverably.
     *
     * <p>Never silent: a failed notification is visible to the user and
     * re-deliverable, because quietly losing "your key was revoked" is the worst
     * outcome this subsystem has.
     */
    FAILED,

    /**
     * Not sent because the user turned this channel off.
     *
     * <p>Recorded rather than discarded, so a user who later wonders why they
     * were not told can be shown that they had opted out.
     */
    SUPPRESSED;

    public boolean isTerminal() {
        return this == ACCEPTED || this == DELIVERED || this == FAILED || this == SUPPRESSED;
    }

    /** Whether the queue should pick this up again. */
    public boolean isRetryableState() {
        return this == PENDING || this == RETRY_SCHEDULED;
    }
}