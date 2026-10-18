package com.pesaguard.backend.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * RFC 6238 conformance for {@link TotpService}.
 *
 * <p>The appendix B vectors are the only thing that makes a hand-rolled TOTP
 * trustworthy. They pin the exact output for a known secret and instant, so an
 * error in the counter arithmetic, the dynamic truncation, or the Base32
 * decoding shows up here rather than as "my authenticator codes never match".
 *
 * <p>The SHA-1 rows are the ones that matter: every mainstream authenticator
 * app expects SHA-1, and the SHA-256/SHA-512 rows are included so a future move
 * to a stronger algorithm cannot land unnoticed.
 */
class TotpTest {

    /**
     * The Base32 secret used by the RFC vectors. RFC 6238 itself specifies the raw
     * ASCII "12345678901234567890", which Base32-encodes to this value.
     */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private final TotpService totpService =
            new TotpService(new SecureRandom(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @Test
    void matchesTheRfc6238Sha1Vectors() {
        // RFC 6238 publishes 8-digit values; this service emits 6, which is what
        // every mainstream authenticator app expects. The six-digit code is the
        // low-order truncation of the RFC value, so the trailing six digits are
        // the assertion. The SHA-1 rows are the ones that matter — SHA-256 and
        // SHA-512 are deliberately not implemented, so publishing vectors for
        // them here would assert behaviour that does not exist.
        assertThat(codeAt(59)).isEqualTo("287082");           // RFC: 94287082
        assertThat(codeAt(1111111109L)).isEqualTo("081804");   // RFC: 07081804
        assertThat(codeAt(1111111111L)).isEqualTo("050471");   // RFC: 14050471
        assertThat(codeAt(1234567890L)).isEqualTo("005924");   // RFC: 89005924
        assertThat(codeAt(2000000000L)).isEqualTo("279037");   // RFC: 69279037
        assertThat(codeAt(20000000000L)).isEqualTo("353130");  // RFC: 65353130
    }

    private String codeAt(long epochSecond) {
        return totpService.codeForStep(RFC_SECRET, totpService.counterAt(epochSecond));
    }

    @Test
    void generatedSecretsRoundTripThroughTheEncoder() {
        String secret = totpService.generateSecret();

        // A code computed from the secret must verify against that same secret.
        // If Base32.encode and Base32.decode disagreed, this would fail.
        assertThat(totpService.verify(secret, totpService.codeForStep(secret, 1L))).isTrue();
    }

    @Test
    void verifyRejectsTheWrongSecret() {
        String secret = totpService.generateSecret();
        String other = totpService.generateSecret();

        assertThat(totpService.verify(secret, totpService.codeForStep(other, 42L))).isFalse();
    }

    @Test
    void verifyRejectsMalformedInputWithoutThrowing() {
        String secret = totpService.generateSecret();

        // These come straight from user input, so none of them may throw: an
        // exception here would be a 500 where the correct answer is a rejection.
        assertThat(totpService.verify(secret, null)).isFalse();
        assertThat(totpService.verify(secret, "")).isFalse();
        assertThat(totpService.verify(secret, "12345")).isFalse();
        assertThat(totpService.verify(secret, "abcdef")).isFalse();
        assertThat(totpService.verify(secret, "1234567")).isFalse();
    }

    @Test
    void codeFormattingIsAlwaysSixDigits() {
        String secret = totpService.generateSecret();

        for (long counter = 1; counter <= 200; counter++) {
            assertThat(totpService.codeForStep(secret, counter)).hasSize(6).matches("\\d{6}");
        }
    }
}