package com.pesaguard.backend.sandbox.domain;

/**
 * How a sandbox execution ended.
 *
 * <p>{@code DENIED} is distinct from {@code FAILED} on purpose: a denied
 * execution is the isolation guard or a quota doing its job, which is the
 * expected result of a well-behaved sandbox. Conflating the two would make the
 * success rate of a sandbox look bad when it is in fact well isolated.
 */
public enum SandboxExecutionOutcome {
    /** Completed as intended. */
    SUCCEEDED,
    /** Ran and failed: a handler threw, or returned an unexpected result. */
    FAILED,
    /** Refused before execution: isolation violation or quota exceeded. */
    DENIED,
    /** Exceeded the sandbox's timeout. */
    TIMED_OUT,
    /** Completed, but the response was truncated to fit the sandbox's limit. */
    TRUNCATED
}