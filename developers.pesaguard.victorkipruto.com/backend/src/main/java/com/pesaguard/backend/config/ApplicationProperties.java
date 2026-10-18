package com.pesaguard.backend.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "pesaguard")
public record ApplicationProperties(@Valid @NotNull Security security,
        @Valid @NotNull Platform platform,
        @Valid @NotNull JsonWebToken jsonWebToken) {
    public ApplicationProperties {
        if (security != null) {
            security.validateOrigins();
        }
    }

    /**
     * Asymmetric signing keys for access tokens.
     *
     * <p>A separate record so that the key material governing who may hold a session
     * is visibly distinct from the HMAC keys guarding stored credentials, and so a
     * reader cannot assume the two are rotated on the same schedule. They are not:
     * the JWT key must exist on every node that verifies tokens, whereas the HMAC
     * keys only ever matter to the one node writing hashes.
     *
     * <p>{@code keyId} is published in the JWT header and recorded with each session.
     * Publishing it is what makes rotation possible without a flag day: while the
     * old private key signs, tokens carrying the new {@code keyId} still verify,
     * because the public half of both keys is trusted during the overlap.
     */
    public record JsonWebToken(
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotBlank String keyId,
            /**
             * PKCS#8 base64 private key. Required even though only signing uses it,
             * because refusing to start without it is safer than silently falling
             * back to a shared secret when a deployment is misconfigured.
             */
            @NotBlank String privateKey,
            /**
             * X.509 base64 public key, used to verify. Optional only so a single-node
             * deployment can derive it from {@code privateKey}; a real deployment
             * supplies both so verifiers need not hold signing material.
             */
            String publicKey,
            @NotNull Duration accessTokenTtl) {
    }

    public record Security(
            @NotEmpty List<URI> allowedOrigins,
            @NotNull Duration sessionTtl,
            /**
             * How long one refresh token remains redeemable before it must be
             * rotated. Longer than {@code sessionTtl} by design: the access token
             * is short-lived and re-minted from the refresh token, so this is how
             * long a signed-in user stays signed in.
             *
             * <p>Optional, defaulting to 30 days in {@code RefreshTokenService}.
             * Left nullable so an existing deployment without the key still
             * starts; a required field would fail startup for a config change
             * that is not what this controls.
             */
            Duration refreshTokenTtl,
            @NotNull Duration passwordResetTtl,
            @NotBlank String credentialHmacKey,
            @NotBlank String auditHmacKey,
            boolean registrationEnabled,
            /**
             * Argon2id parameters, per RFC 9106 / OWASP guidance.
             *
             * <p>{@code memoryKilobytes} is the security-critical parameter: it is what
             * makes guessing expensive in parallel hardware, and it is the number an
             * attacker cannot trade away. The default of 19456 (19 MiB) is the OWASP
             * first-pass baseline and should be re-measured against the deployment
             * hardware rather than assumed, since too low a value silently weakens
             * every password in the table.
             *
             * <p>The upper bound is 1 GiB, not a token 1 MiB cap: this is a
             * memory-hard KDF and the whole point is that operators may want to
             * spend more memory to buy more resistance. A cap below the shipped
             * default would make the application unable to start with its own
             * configuration.
             */
            @Min(64) @Max(1048576) int argon2MemoryKilobytes,
            @Min(1) @Max(16) int argon2Iterations,
            @Min(1) @Max(16) int argon2Parallelism,
            /**
             * Retained only to verify hashes written before the Argon2 migration.
             *
             * <p>Never used to create a new hash. BCrypt is retained as a decode path
             * so existing accounts keep working during the transition and are rehashed
             * to Argon2id on their next successful sign-in.
             */
            @Min(4) @Max(16) int bcryptStrength,
            @Min(1) @Max(100) int accountLoginFailureLimit,
            @Min(1) @Max(1000) int ipLoginFailureLimit,
            @NotNull Duration loginFailureWindow,
            @NotNull Duration invitationTtl,
            @Min(1) @Max(1000) int invitationAcceptAttemptLimit,
            @NotNull Duration invitationAcceptWindow) {

        private void validateOrigins() {
            if (allowedOrigins == null || allowedOrigins.isEmpty()) {
                throw new IllegalArgumentException("At least one allowed origin is required");
            }
            boolean hasUnsafeOrigin = allowedOrigins.stream()
                    .anyMatch(origin -> "*".equals(origin.getHost()) || origin.getHost() == null);
            if (hasUnsafeOrigin) {
                throw new IllegalArgumentException("Wildcard CORS origins are not permitted");
            }
        }

        public void validateKeyMaterial() {
            validateBase64Key(credentialHmacKey, "credentialHmacKey");
            validateBase64Key(auditHmacKey, "auditHmacKey");
            if (credentialHmacKey.equals(auditHmacKey)) {
                throw new IllegalArgumentException("Credential and audit HMAC keys must be distinct");
            }
        }

        private static void validateBase64Key(String encoded, String name) {
            try {
                byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
                if (decoded.length < 32) {
                    throw new IllegalArgumentException(name + " must decode to at least 32 bytes");
                }
            } catch (IllegalArgumentException exception) {
                if (exception.getMessage() != null && exception.getMessage().contains("at least 32 bytes")) {
                    throw exception;
                }
                throw new IllegalArgumentException(name + " must be valid Base64", exception);
            }
        }
    }

    /**
     * Internal operator administration settings.
     *
     * <p>A separate record rather than more fields on Security, so a reader cannot
     * assume operator configuration is governed by the same rules as developer security.
     */
    public record Platform(@NotBlank String operatorHmacKey) {
    }
}
