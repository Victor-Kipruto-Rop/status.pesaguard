package com.pesaguard.backend.events.domain;

/** Subscription lifecycle. {@code CANCELLED} is terminal. */
public enum SubscriptionStatus {
    /** Receiving events. */
    ACTIVE,
    /** Temporarily paused. Resumable; no events delivered while paused. */
    SUSPENDED,
    /** Terminal. No events delivered, and cannot be resumed. */
    CANCELLED
}