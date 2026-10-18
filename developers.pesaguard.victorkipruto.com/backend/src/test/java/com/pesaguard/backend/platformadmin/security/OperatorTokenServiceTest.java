package com.pesaguard.backend.platformadmin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;

/**
 * Operator token issue and verification.
 *
 * <p>The property that carries the whole phase: a token that is not an operator
 * token cannot become one, no matter how it is presented. If a developer session
 * token or API key could authenticate here, the separation between the developer
 * API and internal administration would be a naming convention rather than a
 * control.
 */
class OperatorTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-15T14:37:52Z");
    private static final byte[] KEY = "operator-key-material-for-tests-0001".getBytes();

    private OperatorTokenService service;

    @BeforeEach
    void setUp() {
        service = new OperatorTokenService(
                new SecretKeySpec(KEY, "HmacSHA256"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String token(Set<OperatorCapability> capabilities, Duration ttl) {
        return service.issue(UUID.randomUUID(), "ops@example.com", capabilities, ttl);
    }

    @Test
    void aFreshTokenAuthenticates() {
        String issued = token(Set.of(OperatorCapability.ORGANIZATIONS_READ), Duration.ofHours(1));

        assertThat(service.authenticate(issued)).isPresent();
    }

    @Test
    void capabilitiesSurviveIssuance() {
        String issued = token(EnumSet.of(OperatorCapability.CREDENTIALS_READ,
                OperatorCapability.CREDENTIALS_REVOKE), Duration.ofHours(1));

        AuthenticatedOperator operator = service.authenticate(issued).orElseThrow();

        assertThat(operator.can(OperatorCapability.CREDENTIALS_READ)).isTrue();
        assertThat(operator.can(OperatorCapability.CREDENTIALS_REVOKE)).isTrue();
        assertThat(operator.can(OperatorCapability.PLATFORM_CONFIG_WRITE)).isFalse();
    }

    @Test
    void anExpiredTokenIsRefused() {
        String issued = token(Set.of(OperatorCapability.ORGANIZATIONS_READ), Duration.ofMinutes(5));

        // Same service clock, advanced past expiry.
        OperatorTokenService later = new OperatorTokenService(new SecretKeySpec(KEY, "HmacSHA256"),
                Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC));

        assertThat(later.authenticate(issued)).isEmpty();
    }

    @Test
    void aDeveloperSessionTokenIsNotAnOperatorToken() {
        // A session cookie value, which is what the developer chain accepts. It has
        // no operator prefix, so it is never even parsed.
        assertThat(service.authenticate("a1b2c3d4-e5f6-7890-abcd-ef1234567890")).isEmpty();
    }

    @Test
    void anApiKeyIsNotAnOperatorToken() {
        // The realistic confusion: a long opaque secret presented as a bearer token.
        assertThat(service.authenticate("pgsk_live_4eC39HqLyjWDarjtT1zdp7dc")).isEmpty();
    }

    @Test
    void aTokenSignedWithADifferentKeyIsRefused() {
        String issued = token(Set.of(OperatorCapability.ORGANIZATIONS_READ), Duration.ofHours(1));
        OperatorTokenService other = new OperatorTokenService(
                new SecretKeySpec("a-completely-different-operator-key-01".getBytes(), "HmacSHA256"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(other.authenticate(issued)).isEmpty();
    }

    @Test
    void aTamperedPayloadIsRefused() {
        String issued = token(Set.of(OperatorCapability.ORGANIZATIONS_READ), Duration.ofHours(1));
        String body = issued.substring("pgop_".length());
        String[] parts = body.split("\\.");
        // Rewrite the payload, keep the original signature.
        String forged = "pgop_"
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        "00000000-0000-0000-0000-000000000001\nops@example.com\n9999999999\nPLATFORM_CONFIG_WRITE,"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + parts[1];

        assertThat(service.authenticate(forged)).isEmpty();
    }

    @Test
    void aGarbageTokenIsRefusedWithoutThrowing() {
        // Probing must not be able to distinguish "malformed" from "forged".
        assertThat(service.authenticate("pgop_")).isEmpty();
        assertThat(service.authenticate("pgop_abc")).isEmpty();
        assertThat(service.authenticate("pgop_!!!.###")).isEmpty();
        assertThat(service.authenticate("")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
    }

    @Test
    void anAbsurdlyLongTokenIsRefused() {
        // A bounded parse, so a large request body cannot be used to burn CPU here.
        assertThat(service.authenticate("pgop_" + "a".repeat(10_000))).isEmpty();
    }

    @Test
    void aTokenWithNoCapabilitiesIsValidButPowerless() {
        String issued = token(Set.of(), Duration.ofHours(1));

        AuthenticatedOperator operator = service.authenticate(issued).orElseThrow();

        assertThat(operator.capabilities()).isEmpty();
        assertThat(operator.can(OperatorCapability.ORGANIZATIONS_READ)).isFalse();
    }

    @Test
    void anUnknownCapabilityInATokenIsDroppedNotFatal() {
        // Fail-safe: an operator with one stale capability keeps the rest rather
        // than losing access entirely.
        String payload = UUID.randomUUID() + "\nops@example.com\n"
                + (NOW.plusSeconds(3600).getEpochSecond())
                + "\nORGANIZATIONS_READ,SOME_CAPABILITY_THAT_WAS_REMOVED,";
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String signature = com.pesaguard.backend.security.credentials.CredentialCryptoService
                .hmacSha256(new SecretKeySpec(KEY, "HmacSHA256"), payload);
        String issued = "pgop_" + encoded + "." + signature;

        AuthenticatedOperator operator = service.authenticate(issued).orElseThrow();

        assertThat(operator.can(OperatorCapability.ORGANIZATIONS_READ)).isTrue();
        // The unknown token was dropped rather than failing the whole token.
        assertThat(operator.capabilities()).containsExactly(OperatorCapability.ORGANIZATIONS_READ);
    }

    @Test
    void issuingRequiresAPositiveLifetime() {
        // An operator token that never expires is a permanent credential to every
        // tenant's data.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.issue(UUID.randomUUID(), "ops", Set.of(), Duration.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
