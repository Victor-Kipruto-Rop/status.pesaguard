package com.pesaguard.backend.security.servicejwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.pesaguard.backend.config.PipelineServiceJwtProperties;

class PipelineServiceJwtServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Test
    void issuesShortLivedRs256SyncTokenWithExpectedContractAndUniqueJti() throws Exception {
        KeyPair active = rsaKeyPair();
        PipelineServiceJwtService service = service("active-2026", active, Map.of());

        SignedJWT first = SignedJWT.parse(service.issueSyncToken());
        SignedJWT second = SignedJWT.parse(service.issueSyncToken());

        assertThat(first.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(first.getHeader().getKeyID()).isEqualTo("active-2026");
        assertThat(first.verify(new RSASSAVerifier((RSAPublicKey) active.getPublic()))).isTrue();
        assertThat(first.getJWTClaimsSet().getIssuer()).isEqualTo("developer-platform");
        assertThat(first.getJWTClaimsSet().getAudience()).containsExactly("core-api");
        assertThat(first.getJWTClaimsSet().getSubject()).isEqualTo("svc-developer-platform");
        assertThat(first.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(first.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(NOW.plusSeconds(120));
        assertThat(first.getJWTClaimsSet().getStringListClaim("scope")).containsExactly("service:sync");
        assertThat(first.getJWTClaimsSet().getJWTID()).isNotEqualTo(second.getJWTClaimsSet().getJWTID());
    }

    @Test
    void servesOnlyTheActiveAndConfiguredOverlapPublicKeys() throws Exception {
        KeyPair active = rsaKeyPair();
        KeyPair overlap = rsaKeyPair();
        PipelineServiceJwtService service = service(
                "active-2026", active, Map.of("overlap-2025", publicPem(overlap)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) service.publicJwks().get("keys");

        assertThat(keys).hasSize(2);
        assertThat(keys).extracting(key -> key.get("kid"))
                .containsExactly("active-2026", "overlap-2025");
        assertThat(keys).allSatisfy(key -> {
            assertThat(key.get("kty")).isEqualTo("RSA");
            assertThat(key.get("alg")).isEqualTo("RS256");
            assertThat(key).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi", "oth");
        });
        assertThat(JWKSet.parse(service.publicJwks()).getKeys())
                .extracting(key -> key.getKeyID())
                .containsExactly("active-2026", "overlap-2025");
    }

    @Test
    void jwksResponseAdvertisesPublicKeyCachingForSixtySeconds() throws Exception {
        PipelineServiceJwtService service = service("active-2026", rsaKeyPair(), Map.of());
        PipelineServiceJwksController controller = new PipelineServiceJwksController(service);

        var response = controller.jwks();

        assertThat(response.getHeaders().getCacheControl())
                .contains("max-age=60")
                .contains("public");
        assertThat(response.getBody()).containsKey("keys");
        assertThat(response.getBody().toString()).doesNotContain("\"d\"");
    }

    @Test
    void rejectsEnabledJwtModeWithoutUsableSigningKeyAndInvalidTtl() throws Exception {
        PipelineServiceJwtProperties missingKey = new PipelineServiceJwtProperties();
        missingKey.setMode("DUAL_REQUIRED");
        PipelineServiceJwtService missingKeyService = new PipelineServiceJwtService(
                missingKey, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatIllegalStateException().isThrownBy(missingKeyService::validateConfiguredMode);

        PipelineServiceJwtProperties invalidTtl = properties("kid", rsaKeyPair(), Map.of());
        invalidTtl.setTtlSeconds(301);
        PipelineServiceJwtService invalidTtlService = new PipelineServiceJwtService(
                invalidTtl, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatIllegalStateException().isThrownBy(invalidTtlService::validateConfiguredMode);

        PipelineServiceJwtProperties invalidMode = new PipelineServiceJwtProperties();
        invalidMode.setMode("UNKNOWN");
        PipelineServiceJwtService invalidModeService = new PipelineServiceJwtService(
                invalidMode, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatIllegalStateException().isThrownBy(invalidModeService::validateConfiguredMode);
    }

    private static PipelineServiceJwtService service(String kid, KeyPair active, Map<String, String> overlap)
            throws Exception {
        return new PipelineServiceJwtService(
                properties(kid, active, overlap), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PipelineServiceJwtProperties properties(
            String kid, KeyPair active, Map<String, String> overlap) throws Exception {
        PipelineServiceJwtProperties properties = new PipelineServiceJwtProperties();
        properties.setMode("DUAL_REQUIRED");
        properties.setActiveKid(kid);
        properties.setPrivateKeyPem(privatePem(active));
        properties.setOverlapPublicKeys(overlap);
        properties.setTtlSeconds(120);
        return properties;
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String privatePem(KeyPair pair) {
        return pem("PRIVATE KEY", pair.getPrivate().getEncoded());
    }

    private static String publicPem(KeyPair pair) {
        return pem("PUBLIC KEY", pair.getPublic().getEncoded());
    }

    private static String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded)
                + "\n-----END " + label + "-----";
    }
}
