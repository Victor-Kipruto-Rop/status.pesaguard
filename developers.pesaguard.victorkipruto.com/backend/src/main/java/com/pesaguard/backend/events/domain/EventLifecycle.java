package com.pesaguard.backend.events.domain;

/**
 * Event lifecycle.
 *
 * <pre>
 * DRAFT --activate--&gt; ACTIVE --deprecate--&gt; DEPRECATED --retire--&gt; RETIRED
 * </pre>
 *
 * <p>{@code RETIRED} is terminal and means "no longer emitted". Reaching it is
 * gated on the sunset date rather than on operator enthusiasm: an event is not
 * retired while subscribers still depend on it and the sunset has not passed.
 */
public enum EventLifecycle {
    /** Registered but never emitted. Lets a schema be reviewed before anything depends on it. */
    DRAFT,
    /** Emitted and deliverable. */
    ACTIVE,
    /** Still emitted, but superseded. Subscribers are told what to migrate to. */
    DEPRECATED,
    /** No longer emitted. Terminal. */
    RETIRED;

    public boolean isEmittable() {
        return this == ACTIVE || this == DEPRECATED;
    }

    public boolean isTerminal() {
        return this == RETIRED;
    }
}