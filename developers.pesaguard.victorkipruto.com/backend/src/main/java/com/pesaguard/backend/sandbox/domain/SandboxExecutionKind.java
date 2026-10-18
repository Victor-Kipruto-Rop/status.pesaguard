package com.pesaguard.backend.sandbox.domain;

/**
 * What a sandbox execution was testing.
 *
 * <p>These are the six things a developer needs to be able to exercise, and they
 * are distinct because each fails differently: an auth test that passes tells you
 * nothing about whether your webhook signature is right.
 */
public enum SandboxExecutionKind {
    /** A single request built by hand. */
    TEST_REQUEST,
    /** A scripted multi-step sequence. */
    API_FLOW,
    /** A deliberately malformed request, to check error handling. */
    ERROR_RESPONSE,
    /** Whether the sandbox credential authenticates. */
    AUTHENTICATION,
    /** A webhook endpoint registration or delivery attempt. */
    WEBHOOK,
    /** An emitted event and its delivery to a subscriber. */
    EVENT;

    public static SandboxExecutionKind parse(String value) {
        if (value == null) {
            return null;
        }
        for (SandboxExecutionKind kind : values()) {
            if (kind.name().equalsIgnoreCase(value.trim())) {
                return kind;
            }
        }
        return null;
    }
}