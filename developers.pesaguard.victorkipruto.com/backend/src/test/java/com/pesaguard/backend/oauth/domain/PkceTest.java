package com.pesaguard.backend.oauth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PkceTest {

    /** RFC 7636 appendix B test vector. */
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String EXPECTED_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    void computesTheRfc7636S256Challenge() {
        assertThat(Pkce.challengeFor(VERIFIER)).isEqualTo(EXPECTED_CHALLENGE);
    }

    @Test
    void verifiesAMatchingVerifier() {
        assertThat(Pkce.verify(VERIFIER, EXPECTED_CHALLENGE)).isTrue();
    }

    @Test
    void rejectsAMismatchedVerifier() {
        assertThat(Pkce.verify(VERIFIER, "some-other-challenge-value")).isFalse();
        assertThat(Pkce.verify("x".repeat(43), EXPECTED_CHALLENGE)).isFalse();
    }

    @Test
    void thePlainMethodIsNotSupported() {
        assertThat(Pkce.isSupportedMethod("plain")).isFalse();
        assertThat(Pkce.isSupportedMethod("S256")).isTrue();
        assertThat(Pkce.isSupportedMethod("s256")).isTrue();
        assertThat(Pkce.isSupportedMethod(null)).isFalse();
        assertThat(Pkce.isSupportedMethod("")).isFalse();
    }

    @Test
    void verifierLengthBoundsFollowTheRfc() {
        assertThat(Pkce.isValidVerifier("a".repeat(42))).isFalse();
        assertThat(Pkce.isValidVerifier("a".repeat(43))).isTrue();
        assertThat(Pkce.isValidVerifier("a".repeat(128))).isTrue();
        assertThat(Pkce.isValidVerifier("a".repeat(129))).isFalse();
        assertThat(Pkce.isValidVerifier(null)).isFalse();
    }

    @Test
    void verifierRejectsCharactersOutsideTheUnreservedSet() {
        assertThat(Pkce.isValidVerifier("a".repeat(42) + "+")).isFalse();
        assertThat(Pkce.isValidVerifier("a".repeat(42) + "/")).isFalse();
        assertThat(Pkce.isValidVerifier("a".repeat(42) + "=")).isFalse();
        assertThat(Pkce.isValidVerifier("a".repeat(42) + " ")).isFalse();
    }

    @Test
    void verifierAllowsTheFullUnreservedCharacterSet() {
        assertThat(Pkce.isValidVerifier("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"))
                .isTrue();
    }

    @Test
    void verificationFailsClosedOnMissingInputs() {
        assertThat(Pkce.verify(null, EXPECTED_CHALLENGE)).isFalse();
        assertThat(Pkce.verify(VERIFIER, null)).isFalse();
        assertThat(Pkce.verify(VERIFIER, "")).isFalse();
        assertThat(Pkce.verify("short", EXPECTED_CHALLENGE)).isFalse();
    }
}