package com.pesaguard.backend.sandbox.application;

import com.pesaguard.backend.sandbox.domain.SandboxIsolation;

/**
 * What a sandbox operation produced.
 *
 * @param statusCode  the status the caller should observe, or null if none applies
 * @param body        a short, non-sensitive description of the outcome
 * @param truncated   whether the body was cut to fit the sandbox's response limit
 */
public record SandboxOperationResult(Integer statusCode, String body, boolean truncated) {

    public static SandboxOperationResult ok(String body) {
        return new SandboxOperationResult(200, body, false);
    }

    public static SandboxOperationResult status(int statusCode, String body) {
        return new SandboxOperationResult(statusCode, body, false);
    }

    public static SandboxOperationResult truncated(String body) {
        return new SandboxOperationResult(200, body, true);
    }
}