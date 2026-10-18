package com.pesaguard.backend.explorer.security;

/**
 * A target the explorer refused to contact.
 *
 * <p>Separate from {@code BusinessException} on purpose: this is not a client
 * mistake that should be echoed back as a 400 with detail, it is a security
 * refusal. The explorer translates it into a generic error so that the response
 * does not tell an attacker which of their probes got furthest.
 */
public class UnsafeTargetException extends RuntimeException {

    public UnsafeTargetException(String message) {
        super(message);
    }
}