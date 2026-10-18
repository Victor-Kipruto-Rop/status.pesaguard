package com.pesaguard.backend.security.servicejwt;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.pesaguard.backend.config.PipelineServiceJwtProperties;

@Service
public class PipelineServiceJwtService {

    private static final Pattern KID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final String PRIVATE_KEY_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_KEY_FOOTER = "-----END PRIVATE KEY-----";
    private static final String PUBLIC_KEY_HEADER = "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_KEY_FOOTER = "-----END PUBLIC KEY-----";

    private final PipelineServiceJwtProperties properties;
    private final Clock clock;
    private volatile KeyMaterial keyMaterial;
    private volatile PipelineServiceJwtMode mode;

    public PipelineServiceJwtService(PipelineServiceJwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @PostConstruct
    void validateConfiguredMode() {
        mode = PipelineServiceJwtMode.parse(properties.getMode());
        if (mode.jwtEnabled()) {
            keyMaterial();
            int ttl = properties.getTtlSeconds();
            if (ttl < 60 || ttl > 300) {
                throw new IllegalStateException("Pipeline service JWT TTL must be from 60 to 300 seconds.");
            }
            publicJwks();
        }
    }

    public PipelineServiceJwtMode mode() {
        PipelineServiceJwtMode configured = mode;
        if (configured == null) {
            configured = PipelineServiceJwtMode.parse(properties.getMode());
            mode = configured;
        }
        return configured;
    }

    public String issueSyncToken() {
        return issueToken(List.of("service:sync"));
    }

    public String issueKeyLifecycleToken(String operation) {
        String scope = switch (operation) {
            case "revoke" -> "service:key:revoke";
            case "suspend" -> "service:key:suspend";
            default -> throw new IllegalArgumentException("Unsupported API-key lifecycle operation.");
        };
        return issueToken(List.of(scope));
    }

    private String issueToken(List<String> scopes) {
        if (!mode().jwtEnabled()) {
            throw new IllegalStateException("Pipeline service JWT mode is disabled.");
        }
        KeyMaterial material = keyMaterial();
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(properties.getTtlSeconds());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("developer-platform")
                .audience("core-api")
                .subject("svc-developer-platform")
                .issueTime(Date.from(issuedAt))
                .jwtID(UUID.randomUUID().toString())
                .expirationTime(Date.from(expiresAt))
                .claim("scope", scopes)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(properties.getActiveKid()).build(),
                claims);
        try {
            jwt.sign(new RSASSASigner(material.privateKey()));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to sign pipeline service token.", exception);
        }
    }

    public Map<String, Object> publicJwks() {
        List<JWK> publicKeys = new ArrayList<>();
        KeyMaterial active = tryLoadKeyMaterial();
        if (active != null) {
            publicKeys.add(active.publicJwk());
        }
        for (Map.Entry<String, String> entry : properties.getOverlapPublicKeys().entrySet()) {
            validateKid(entry.getKey());
            if (properties.getActiveKid() != null && properties.getActiveKid().equals(entry.getKey())) {
                throw new IllegalStateException("Pipeline service JWT active and overlap key IDs must differ.");
            }
            publicKeys.add(publicJwk(entry.getKey(), parsePublicKey(entry.getValue())));
        }
        return new JWKSet(publicKeys).toJSONObject(true);
    }

    private KeyMaterial keyMaterial() {
        KeyMaterial current = keyMaterial;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = keyMaterial;
            if (current == null) {
                String kid = properties.getActiveKid();
                validateKid(kid);
                RSAPrivateKey privateKey = parsePrivateKey(properties.getPrivateKeyPem());
                if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
                    throw new IllegalStateException("Pipeline service JWT private key must use RSA CRT encoding.");
                }
                RSAPublicKey publicKey;
                try {
                    publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                            new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
                } catch (Exception exception) {
                    throw new IllegalStateException("Pipeline service JWT public key cannot be derived.", exception);
                }
                validateRsaStrength(privateKey);
                validateRsaStrength(publicKey);
                current = new KeyMaterial(privateKey, publicJwk(kid, publicKey));
                keyMaterial = current;
            }
        }
        return current;
    }

    private KeyMaterial tryLoadKeyMaterial() {
        if (properties.getActiveKid() == null || properties.getActiveKid().isBlank()
                || properties.getPrivateKeyPem() == null || properties.getPrivateKeyPem().isBlank()) {
            return null;
        }
        return keyMaterial();
    }

    private static RSAPrivateKey parsePrivateKey(String pem) {
        byte[] der = decodePem(pem, PRIVATE_KEY_HEADER, PRIVATE_KEY_FOOTER, "PKCS#8 private");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception exception) {
            throw new IllegalStateException("Pipeline service JWT private key is invalid.", exception);
        }
    }

    private static RSAPublicKey parsePublicKey(String pem) {
        byte[] der = decodePem(pem, PUBLIC_KEY_HEADER, PUBLIC_KEY_FOOTER, "X.509 public");
        try {
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            validateRsaStrength(key);
            return key;
        } catch (Exception exception) {
            throw new IllegalStateException("Pipeline service JWT overlap public key is invalid.", exception);
        }
    }

    private static byte[] decodePem(String pem, String header, String footer, String description) {
        if (pem == null || !pem.contains(header) || !pem.contains(footer)) {
            throw new IllegalStateException("Pipeline service JWT " + description + " key PEM is not configured.");
        }
        String encoded = pem.replace(header, "").replace(footer, "").replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Pipeline service JWT key PEM is invalid.", exception);
        }
    }

    private static RSAKey publicJwk(String kid, RSAPublicKey key) {
        validateKid(kid);
        validateRsaStrength(key);
        return new RSAKey.Builder(key)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(kid)
                .build();
    }

    private static void validateKid(String kid) {
        if (kid == null || !KID_PATTERN.matcher(kid).matches()) {
            throw new IllegalStateException("Pipeline service JWT key ID is invalid.");
        }
    }

    private static void validateRsaStrength(java.security.interfaces.RSAKey key) {
        if (key.getModulus().bitLength() < 2048) {
            throw new IllegalStateException("Pipeline service JWT RSA keys must be at least 2048 bits.");
        }
    }

    private record KeyMaterial(RSAPrivateKey privateKey, RSAKey publicJwk) {
    }
}
