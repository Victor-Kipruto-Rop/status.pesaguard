package com.pesaguard.backend.platformadmin.domain;

import java.util.Set;
import java.util.UUID;

/**
 * An authenticated internal operator.
 *
 * <p>A <b>distinct type</b>, not a variant of the developer principal. This is
 * the whole separation mechanism: no developer session token can ever be
 * converted into an {@code AuthenticatedOperator}, because the only code that
 * constructs one is the operator token authenticator. A shared principal type
 * with a role flag would put the boundary in application code, where a single
 * missed check exposes every tenant's credentials.
 *
 * <p>Carries <b>no organization</b>. An operator is not scoped to a tenant, and
 * giving one an organization field would invite code that filters by it and
 * silently returns the wrong customer's data.
 *
 * @param operatorId stable identity of the operator
 * @param subject the token's subject, for audit correlation
 * @param capabilities what this operator may do, from the platform namespace
 * @param reason a stated justification, required because operator actions affect
 *        customers who are not in the room
 */
public record AuthenticatedOperator(
        UUID operatorId,
        String subject,
        Set<OperatorCapability> capabilities,
        String reason) {

    public AuthenticatedOperator {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        if (operatorId == null) {
            throw new IllegalArgumentException("An operator requires an identity");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("An operator requires a token subject");
        }
    }

    /** Whether this operator holds a capability. */
    public boolean can(OperatorCapability capability) {
        return capabilities.contains(capability);
    }

    /**
     * Requires a mutating capability.
     *
     * <p>Separate from {@link #can} so a read path can never accidentally perform
     * a write: the mutating entry points call this, and it refuses without the
     * right capability.
     */
    public void require(OperatorCapability capability) {
        if (!can(capability)) {
            throw new OperatorAuthorizationException(
                    "This operator does not hold " + capability + ".");
        }
    }

    /**
     * Requires a stated reason for an action affecting a customer.
     *
     * <p>Not optional. A revoke with no recorded reason cannot be explained to
     * the customer afterwards, and the audit trail would say only that someone
     * with platform access ended a working integration.
     */
    public String requireReason() {
        if (reason == null || reason.isBlank()) {
            throw new OperatorAuthorizationException(
                    "An operator action affecting a customer requires a stated reason.");
        }
        String trimmed = reason.trim();
        // Control characters would corrupt a single-line audit entry.
        StringBuilder cleaned = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (character >= 0x20 && character != 0x7F) {
                cleaned.append(character);
            }
        }
        if (cleaned.isEmpty()) {
            throw new OperatorAuthorizationException(
                    "An operator action affecting a customer requires a stated reason.");
        }
        if (cleaned.length() > MAX_REASON_LENGTH) {
            throw new OperatorAuthorizationException(
                    "A reason must be at most " + MAX_REASON_LENGTH + " characters.");
        }
        return cleaned.toString();
    }

    static final int MAX_REASON_LENGTH = 500;

    /** Raised when an operator lacks a capability or omits a required reason. */
    public static class OperatorAuthorizationException extends RuntimeException {
        public OperatorAuthorizationException(String message) {
            super(message);
        }
    }
}