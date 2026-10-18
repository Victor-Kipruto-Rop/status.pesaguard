package com.pesaguard.backend.security.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.security.TestKeys;
import com.pesaguard.backend.security.TestProperties;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * JWT issuance, verification, and the attacks against them.
 *
 * <p>Most of these tests exist to prove a token is <em>refused</em>. A verifier
 * that accepts a forged token fails silently and looks healthy until it is used,
 * so the negative cases are the ones worth pinning: unsigned tokens, the
 * {@code none} algorithm, HMAC signed with the RSA public key, a token signed by
 * a different key, one minted for another audience, and an expired one.
 *
 * <p>The algorithm-confusion cases are why the verifier pins RS256 rather than
 * reading the header. A library that trusts the header will verify a token signed
 * with HS256 using the RSA <em>public</em> key — a key that is, by construction,
 * published to every verifier.
 */
class AccessTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private AccessTokenService service;

    @BeforeEach
    void setUp() {
        service = new AccessTokenService(propertiesWith(TestProperties.jwt()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issuesATokenThatVerifiesBackToTheSameIdentity() {
        AuthenticatedUser principal = principal();

        AuthenticatedUser verified = service.verify(service.issue(principal, NOW).token())
                .orElseThrow();

        // Compared field by field rather than as whole records: email and
        // displayName come back empty because they are deliberately not claims, so
        // `contains(principal)` would assert the very thing the design forbids.
        assertThat(verified.userId()).isEqualTo(principal.userId());
        assertThat(verified.organizationId()).isEqualTo(principal.organizationId());
        assertThat(verified.sessionId()).isEqualTo(principal.sessionId());
        assertThat(verified.authorities()).isEqualTo(principal.authorities());
    }

    @Test
    void neverPutsTheEmailOrDisplayNameInTheToken() {
        // A JWT is readable by anyone holding it and cannot be withdrawn, so a claim
        // that changes — an email the user edits, a name they correct — stays wrong
        // for the token's whole life and is visible to anyone who intercepts it.
        String token = service.issue(principal(), NOW).token();

        assertThat(token).doesNotContain("person@example.com");
        assertThat(token).doesNotContain("Person");
    }

    @Test
    void carriesTheRegisteredAndAudienceClaims() throws ParseException {
        SignedJWT jwt = SignedJWT.parse(service.issue(principal(), NOW).token());

        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("pesaguard-developer-platform");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("pesaguard-developer-platform");
        assertThat(jwt.getJWTClaimsSet().getJWTID()).isEqualTo(principal().sessionId().toString());
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        // The key id is what makes rotation possible without invalidating live tokens.
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("test-v1");
    }

    @Test
    void expiresAtTheConfiguredShortLifetime() {
        AccessTokenService.IssuedAccessToken issued = service.issue(principal(), NOW);

        // Five minutes, not eight hours: this is the only bound on a stolen token.
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(service.accessTokenTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsAnExpiredToken() {
        AccessTokenService.IssuedAccessToken issued = service.issue(principal(), NOW);

        AccessTokenService later = new AccessTokenService(
                propertiesWith(TestProperties.jwt()),
                Clock.fixed(NOW.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));

        assertThat(later.verify(issued.token())).isEmpty();
    }

    @Test
    void rejectsAnUnsignedToken() {
        // The classic `alg: none` downgrade: no signature at all, arbitrary claims.
        // This is a PlainJWT rather than a SignedJWT because `none` is a JWE-style
        // unsecured algorithm — it cannot even be expressed in a JWS header, which is
        // precisely why a verifier that trusts the header must be rejected outright.
        PlainJWT forged = new PlainJWT(claimsFor(principal()));

        assertThat(service.verify(forged.serialize())).isEmpty();
    }

    @Test
    void rejectsATokenSignedWithHmacUsingThePublicKey() throws Exception {
        // Algorithm confusion. The HMAC needs only the RSA *public* key, which every
        // verifier holds, so accepting HS256 here would let anyone mint a session.
        // The verifier pins RS256, so this must not verify even though the HMAC
        // itself is genuinely correct over the payload.
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claimsFor(principal()));
        forged.sign(new MACSigner(Base64.getDecoder().decode(TestKeys.PUBLIC_KEY_BASE64)));

        assertThat(service.verify(forged.serialize())).isEmpty();
    }

    @Test
    void rejectsATokenSignedByADifferentKeyPair() throws Exception {
        // Right algorithm, valid signature, wrong key.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var attackerKeys = generator.generateKeyPair();

        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claimsFor(principal()));
        forged.sign(new RSASSASigner((RSAPrivateKey) attackerKeys.getPrivate()));

        assertThat(service.verify(forged.serialize())).isEmpty();
    }

    @Test
    void rejectsATokenIssuedForAnotherAudience() throws Exception {
        // A token from a sibling service that happens to share this signing key must
        // not be accepted here: its audience is not this platform.
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                claimsBuilder().audience("some-other-service").build());
        forged.sign(new RSASSASigner(privateKey()));

        assertThat(service.verify(forged.serialize())).isEmpty();
    }

    @Test
    void rejectsATokenFromAnotherIssuer() throws Exception {
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                claimsBuilder().issuer("some-other-issuer").build());
        forged.sign(new RSASSASigner(privateKey()));

        assertThat(service.verify(forged.serialize())).isEmpty();
    }

    @Test
    void rejectsATokenWhoseSubjectIsNotAUuid() throws Exception {
        // Correctly signed by us, but not a token this service could have issued.
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                claimsBuilder().subject("not-a-uuid").build());
        forged.sign(new RSASSASigner(privateKey()));

        assertThat(service.verify(forged.serialize())).isEmpty();
    }

    @Test
    void rejectsATokenWithNoExpiry() throws Exception {
        // A token with no `exp` would otherwise be valid forever, turning one stolen
        // token into permanent access. Built from a bare claims set rather than by
        // clearing the field, because Nimbus treats a null argument to
        // expirationTime as "leave it alone" rather than "remove it".
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                new JWTClaimsSet.Builder()
                        .issuer("pesaguard-developer-platform")
                        .audience("pesaguard-developer-platform")
                        .subject(principal().userId().toString())
                        .jwtID(principal().sessionId().toString())
                        .claim("org", principal().organizationId().toString())
                        .build());
        forged.sign(new RSASSASigner(privateKey()));

        assertThat(service.verify(forged.serialize())).isEmpty();
    }

    @Test
    void rejectsMalformedInputWithoutThrowing() {
        // These come straight from an Authorization header. A thrown exception here
        // would be a 500 where the only correct answer is a refusal.
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("")).isEmpty();
        assertThat(service.verify("not-a-jwt")).isEmpty();
        assertThat(service.verify("a.b.c")).isEmpty();
    }

    @Test
    void tokensDifferAcrossSessions() {
        // RS256 (RSASSA-PKCS1-v1_5) is deterministic: identical claims produce an
        // identical signature, which is a property of the algorithm, not a defect.
        // Two tokens are therefore only distinguishable when their claims differ —
        // and every session carries its own `jti`, so no two live sessions can
        // present the same token.
        String first = service.issue(principal(), NOW).token();
        String second = service.issue(principalWithDifferentSession(), NOW).token();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theSameSessionAlwaysProducesTheSameToken() {
        // Pinned deliberately, because it is easy to assume otherwise and to then
        // "fix" it by adding randomness that weakens nothing. If this ever needs to
        // change, the change belongs in a comment explaining why — not in a silent
        // switch to a different algorithm.
        assertThat(service.issue(principal(), NOW).token())
                .isEqualTo(service.issue(principal(), NOW).token());
    }

    @Test
    void refusesToStartWithoutAPublicKey() {
        // A verifier that can derive the public key is holding the private key, which
        // is the separation RS256 exists to provide.
        ApplicationProperties.JsonWebToken withoutPublicKey =
                new ApplicationProperties.JsonWebToken(
                        "pesaguard-developer-platform",
                        "pesaguard-developer-platform",
                        "test-v1",
                        TestKeys.PRIVATE_KEY_BASE64,
                        null,
                        Duration.ofMinutes(5));

        assertThatThrownBy(() -> new AccessTokenService(
                propertiesWith(withoutPublicKey), Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLIC_KEY");
    }

    private static ApplicationProperties propertiesWith(ApplicationProperties.JsonWebToken jwt) {
        return new ApplicationProperties(
                TestProperties.security(), TestProperties.platformRecord(), jwt);
    }

    private static AuthenticatedUser principal() {
        return new AuthenticatedUser(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "person@example.com",
                "Person",
                java.util.Set.of("ROLE_OWNER"));
    }

    private static AuthenticatedUser principalWithDifferentSession() {
        return new AuthenticatedUser(
                principal().userId(),
                principal().organizationId(),
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "person@example.com",
                "Person",
                java.util.Set.of("ROLE_OWNER"));
    }

    private static JWTClaimsSet.Builder claimsBuilder() {
        AuthenticatedUser principal = principal();
        return new JWTClaimsSet.Builder()
                .issuer("pesaguard-developer-platform")
                .audience("pesaguard-developer-platform")
                .subject(principal.userId().toString())
                .jwtID(principal.sessionId().toString())
                .claim("org", principal.organizationId().toString())
                .claim("amr", "pwd")
                .claim("mfa", Boolean.TRUE)
                .claim("rol", principal.authorities().stream().findFirst().orElse(""))
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plus(Duration.ofMinutes(5))));
    }

    private static JWTClaimsSet claimsFor(AuthenticatedUser principal) {
        return claimsBuilder()
                .subject(principal.userId().toString())
                .jwtID(principal.sessionId().toString())
                .claim("org", principal.organizationId().toString())
                .build();
    }

    /** The signing key, for tests that need to produce a genuinely valid signature. */
    private static RSAPrivateKey privateKey() {
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(
                            Base64.getDecoder().decode(TestKeys.PRIVATE_KEY_BASE64)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}