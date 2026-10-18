package com.pesaguard.backend.common.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApiError(
        @NotBlank String code,
        @NotBlank String message,
        @NotNull UUID requestId,
        @NotNull Instant timestamp,
        List<FieldViolation> violations,
        /**
         * Organizations this account may sign in to, present only when the code is
         * {@code ORGANIZATION_SELECTION_REQUIRED}.
         *
         * <p>A dedicated field rather than a repurposed violation: violations
         * describe rejected input, and a chooser is the opposite — valid input the
         * caller must disambiguate. Overloading it would make a 409 look like a
         * validation failure to every client that reads it that way.
         */
        List<SelectableOrganization> selectableOrganizations,
         /** Set only for a valid-password, unverified-account login challenge. */
         Instant verificationExpiresAt,
         /** Earliest time this caller may request a replacement code. */
         Instant verificationResendAvailableAt,
         /** The registered address to verify after a successful username login. */
         String verificationEmail,
         /** Short-lived token restricted to the MFA enrolment endpoints. */
         String mfaEnrollmentToken,
         Instant mfaEnrollmentExpiresAt,
         UUID loginChallengeId,
         Instant loginChallengeExpiresAt,
         Instant loginChallengeResendAvailableAt,
         String maskedLoginEmail) {

    public ApiError {
        violations = violations == null ? List.of() : List.copyOf(violations);
        selectableOrganizations = selectableOrganizations == null
                ? List.of() : List.copyOf(selectableOrganizations);
    }

    public ApiError(String code, String message, UUID requestId, Instant timestamp,
            List<FieldViolation> violations) {
        this(code, message, requestId, timestamp, violations, List.of(),
                null, null, null, null, null, null, null, null, null);
    }

    public ApiError(String code, String message, UUID requestId, Instant timestamp,
            List<FieldViolation> violations, List<SelectableOrganization> selectableOrganizations) {
        this(code, message, requestId, timestamp, violations, selectableOrganizations,
                null, null, null, null, null, null, null, null, null);
    }

    public ApiError(String code, String message, UUID requestId, Instant timestamp,
            List<FieldViolation> violations, List<SelectableOrganization> selectableOrganizations,
            Instant verificationExpiresAt, Instant verificationResendAvailableAt, String verificationEmail) {
        this(code, message, requestId, timestamp, violations, selectableOrganizations,
                verificationExpiresAt, verificationResendAvailableAt, verificationEmail,
                null, null, null, null, null, null);
    }

    public ApiError(String code, String message, UUID requestId, Instant timestamp,
            List<FieldViolation> violations, List<SelectableOrganization> selectableOrganizations,
            Instant verificationExpiresAt, Instant verificationResendAvailableAt, String verificationEmail,
            String mfaEnrollmentToken, Instant mfaEnrollmentExpiresAt) {
        this(code, message, requestId, timestamp, violations, selectableOrganizations,
                verificationExpiresAt, verificationResendAvailableAt, verificationEmail,
                mfaEnrollmentToken, mfaEnrollmentExpiresAt, null, null, null, null);
    }

    /**
     * One organization offered on a selection challenge.
     *
     * <p>Id, name and slug only. Never a member count, plan, or any other
     * aggregate — this is a chooser for an account that has already proved who it
     * is, and it should not become a way to enumerate the shape of an
     * organization to a partially-authenticated caller.
     */
    public record SelectableOrganization(UUID id, String name, String slug, String role) {
    }
}
