package com.pesaguard.backend.sandbox.domain;

/**
 * Sandbox lifecycle states.
 *
 * <pre>
 * PROVISIONING --activate--&gt; ACTIVE --suspend--&gt; SUSPENDED --resume--&gt; ACTIVE
 *                            |        |                                    |
 *                            |        +--delete--&gt; DELETED &lt;-------------+--delete--&gt;
 *                            |
 *                            +--expire--&gt; EXPIRED --delete--&gt; DELETED
 * </pre>
 *
 * <p>{@code DELETED} is terminal. {@code EXPIRED} is not the same as suspended:
 * an expired sandbox may still hold data that an operator must inspect before
 * deletion, whereas a suspended one is expected to come back.
 */
public enum SandboxStatus {
    /** Created but not yet usable. No execution is permitted. */
    PROVISIONING,
    /** Usable. The only state in which sandbox execution is allowed. */
    ACTIVE,
    /** Temporarily unusable. May be resumed. Data is retained. */
    SUSPENDED,
    /** Past its expiry. May not be resumed; must be deleted. */
    EXPIRED,
    /** Terminal. */
    DELETED;

    public boolean allowsExecution() {
        return this == ACTIVE;
    }

    public boolean isTerminal() {
        return this == DELETED;
    }

    public boolean allowsResume() {
        return this == SUSPENDED;
    }
}