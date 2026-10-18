package com.pesaguard.backend.security.tokens;

import java.security.KeyFactory;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Issues and verifies RS256 access tokens.
 *
 * <p>Asymmetric on purpose. A shared-secret (HMAC) signing key means every node
 * that verifies tokens can also mint them, so a single compromised verifier can
 * forge a session for any user and no key separation exists anywhere in the
 * system. RS256 confines forgery to whoever holds the private key, which is
 * deployed only where tokens are signed.
 *
 * <p><b>The algorithm is pinned on both sides.</b> Verification does not ask the
 * token what algorithm it used; it accepts {@code RS256} and nothing else. This
 * closes the classic JWT attacks: a token signed with {@code none}, or with
 * HS256 using the RSA <em>public</em> key as the HMAC secret — both of which work
 * against libraries that negotiate the algorithm from the header. Deriving the
 * expected algorithm here, rather than reading it, is the entire defence.
 *
 * <p><b>Revocation is by expiry, not by lookup.</b> A stateless token cannot be
 * withdrawn, so the TTL is deliberately short and logout, account lockout and
 * device removal are enforced through the refresh-token family and the emergency
 * denylist rather than through this token. The refresh path is what actually ends
 * a session; this token is the short-lived half.
 */
@Service
public class AccessTokenService {

    private final ApplicationProperties.JsonWebToken properties;
    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final Clock clock;

    public AccessTokenService(ApplicationProperties properties, Clock clock) {
        this.properties = properties.jsonWebToken();
        KeyPair pair = loadKeyPair(this.properties);
        this.privateKey = (RSAPrivateKey) pair.getPrivate();
        this.publicKey = (RSAPublicKey) pair.getPublic();
        this.clock = clock;
    }

    /**
     * Mints a signed access token for a session.
     *
     * <p>{@code jti} is the session id, which is what makes a stolen token
     * attributable: the denylist and any incident review key off the same value.
     */
    public IssuedAccessToken issue(AuthenticatedUser principal, Instant now) {
        Instant expiry = now.plus(accessTokenTtl());
        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .audience(properties.audience())
                .subject(principal.userId().toString())
                .jwtID(principal.sessionId().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                // Tenant scope. Not free to omit: every query in the platform is
                // tenant-scoped through the principal, so a token without it could
                // not be attributed to an organization at all.
                .claim("org", principal.organizationId().toString())
                .claim("amr", "pwd")
                .claim("mfa", !principal.mfaEnrollmentOnly())
                .claim("rol", principal.authorities().stream().findFirst().orElse(""))
                .claim("mfa_enrollment_only", principal.mfaEnrollmentOnly());
        JWTClaimsSet claims = claimsBuilder.build();

        // Header names the key so rotation can overlap: while the old private key
        // signs, a token bearing the new kid still verifies against the trusted
        // public half.
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(properties.keyId())
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(privateKey));
        } catch (JOSEException exception) {
            // Never surfaces the cause: it would describe the key material.
            throw new IllegalStateException("Unable to sign access token", exception);
        }
        return new IssuedAccessToken(jwt.serialize(), expiry, principal.sessionId());
    }

    /**
     * Verifies a token and rebuilds the principal from its claims.
     *
     * @return the principal, or empty if the token is null or malformed, not
     *         signed with the expected algorithm, expired, or issued for another
     *         audience
     */
    public Optional<AuthenticatedUser> verify(String token) {
        // null is reachable from a missing Authorization header being reduced to
        // "no token" by a caller. Failing here with a NullPointerException would
        // surface as a 500 instead of the 401 every other rejection produces.
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException malformed) {
            return Optional.empty();
        }
        // Pin the algorithm. Accepting the header's value would permit `none` and
        // HS256-with-the-public-key, both of which verify attacker-chosen claims.
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            return Optional.empty();
        }
        try {
            if (!jwt.verify(new RSASSAVerifier(publicKey))) {
                return Optional.empty();
            }
        } catch (JOSEException exception) {
            return Optional.empty();
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException malformed) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        Date expiry = claims.getExpirationTime();
        if (expiry == null || !expiry.toInstant().isAfter(now)) {
            return Optional.empty();
        }
        // Both are checked: a token minted for another service sharing this signing
        // key must not be accepted here.
        if (!properties.issuer().equals(claims.getIssuer())
                || claims.getAudience() == null
                || !claims.getAudience().contains(properties.audience())) {
            return Optional.empty();
        }
        try {
            UUID userId = UUID.fromString(claims.getSubject());
            UUID sessionId = UUID.fromString(claims.getJWTID());
            UUID organizationId = UUID.fromString(claims.getStringClaim("org"));
            String role = claims.getStringClaim("rol");
            boolean mfaEnrollmentOnly = Boolean.TRUE.equals(claims.getBooleanClaim("mfa_enrollment_only"));
            Set<String> authorities = role == null || role.isBlank() ? Set.of() : Set.of(role);
            return Optional.of(new AuthenticatedUser(
                    userId, organizationId, sessionId, "", "", authorities,
                    com.pesaguard.backend.organization.domain.OrganizationStatus.ACTIVE,
                    false, Set.of(), mfaEnrollmentOnly));
        } catch (IllegalArgumentException | ParseException malformedClaim) {
            // A well-signed token whose claims are not the shape this service issues
            // is still not a token this service issued.
            return Optional.empty();
        }
    }

    /**
     * How long an access token stays valid.
     *
     * <p>Short by design: this is the only bound on a stolen token's usefulness,
     * because nothing can withdraw it before it expires.
     */
    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }

    /**
     * Loads the signing pair, deriving the public half when only the private key
     * is configured.
     *
     * <p>Deriving is convenient for a single node and refused for a real
     * deployment: a verifier that must derive the public key is holding private
     * key material, which is exactly the separation RS256 exists to provide.
     */
    private static KeyPair loadKeyPair(ApplicationProperties.JsonWebToken properties) {
        try {
            byte[] privateBytes = Base64.getDecoder().decode(stripPem(properties.privateKey()));
            RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
            if (properties.publicKey() != null && !properties.publicKey().isBlank()) {
                byte[] publicBytes = Base64.getDecoder().decode(stripPem(properties.publicKey()));
                RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(publicBytes));
                return new KeyPair(publicKey, privateKey);
            }
            throw new IllegalStateException(
                    "PESAGUARD_JWT_PUBLIC_KEY is required: a verifier that derives the public key "
                            + "from the private key holds signing material on every node");
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            // Deliberately does not echo the key material.
            throw new IllegalStateException("JWT key material is unusable", exception);
        }
    }

    /** Tolerates PEM armour so keys can be pasted straight from a key generator. */
    private static String stripPem(String value) {
        return value.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
    }

    /** A freshly signed access token. */
    public record IssuedAccessToken(String token, Instant expiresAt, UUID sessionId) {
    }
}