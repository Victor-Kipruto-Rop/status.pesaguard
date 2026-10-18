package com.pesaguard.backend.sandbox.security;

/**
 * Raised whenever sandbox code would cross the isolation boundary.
 *
 * <p>Separate from {@code BusinessException} because this is not a client mistake
 * that should be echoed back as a 400 with a helpful message. It represents either
 * a programming error or an attack, and in both cases the response must not tell
 * the caller which check fired or what the production topology looks like.
 */
public class SandboxIsolationViolation extends RuntimeException {

    public SandboxIsolationViolation(String message) {
        super(message);
    }
}