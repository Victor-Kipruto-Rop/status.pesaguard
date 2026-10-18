package com.pesaguard.backend.outbox.domain;

/**
 * Publication state of an outbox row.
 *
 * <p>Deliberately small. A richer state machine (IN_FLIGHT, RETRYING) would need
 * a lease and a sweeper to recover rows abandoned by a crashed publisher, and
 * the recovery would be strictly weaker than the at-least-once guarantee the
 * simple version already gives: a row left PENDING is simply retried.
 */
public enum OutboxStatus {

    /** Waiting to be claimed by a publisher. */
    PENDING,

    /** Handed to the broker. Terminal. */
    PUBLISHED,

    /**
     * Abandoned after exhausting the attempt budget.
     *
     * <p>Not deleted. A dead letter that cannot be read is not a recovery
     * capability, so the payload is retained for replay after the cause is fixed.
     */
    DEAD_LETTERED
}