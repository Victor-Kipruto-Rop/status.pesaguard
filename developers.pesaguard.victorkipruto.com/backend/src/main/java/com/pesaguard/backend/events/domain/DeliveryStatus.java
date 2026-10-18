package com.pesaguard.backend.events.domain;

/**
 * Per-attempt delivery outcome.
 *
 * <p>Each attempt is a separate row. Overwriting the previous outcome would make
 * "how many times did this fail, and how did it fail each time?" unanswerable,
 * which is exactly the question asked when deciding whether to keep retrying an
 * endpoint or disable it.
 */
public enum DeliveryStatus {
    /** Accepted for delivery; no attempt made yet. */
    PENDING,
    /** An attempt is in flight. */
    IN_FLIGHT,
    /** The endpoint returned 2xx. */
    DELIVERED,
    /** The attempt failed and another is scheduled. */
    RETRY_SCHEDULED,
    /** The attempt failed and no attempts remain. */
    FAILED,
    /** Attempts exhausted or the event permanently undeliverable. Needs an operator. */
    DEAD_LETTERED,
    /** Suppressed because the subscription is not active. */
    SUPPRESSED;

    /** Whether the event is still in play and has not been given up on. */
    public boolean isTerminal() {
        return this == DELIVERED || this == FAILED
                || this == DEAD_LETTERED || this == SUPPRESSED;
    }

    public boolean isFailure() {
        return this == FAILED || this == RETRY_SCHEDULED || this == DEAD_LETTERED;
    }
}