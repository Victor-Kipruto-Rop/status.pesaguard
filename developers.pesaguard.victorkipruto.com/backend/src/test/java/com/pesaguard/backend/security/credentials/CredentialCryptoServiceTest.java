package com.pesaguard.backend.security.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class CredentialCryptoServiceTest {

    private final CredentialCryptoService service = new CredentialCryptoService(
            new SecretKeySpec("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8), "HmacSHA256"),
            new SecureRandom());

    @Test
    void sha256IsStableForTheSameValue() {
        assertThat(service.sha256("token-value"))
                .isEqualTo(service.sha256("token-value"))
                .hasSize(64);
    }

    @Test
    void randomTokensAreUrlSafeAndNotReused() {
        String first = service.randomToken(32);
        String second = service.randomToken(32);

        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(second).matches("[A-Za-z0-9_-]{43}")
                .isNotEqualTo(first);
    }

    @Test
    void hmacIsKeyedAndComparisonIsConstantTimeApi() {
        String value = "api-key-value";

        assertThat(service.hmacSha256(value)).hasSize(64)
                .isEqualTo(service.hmacSha256(value));
        assertThat(service.constantTimeEquals("same", "same")).isTrue();
        assertThat(service.constantTimeEquals("same", "different")).isFalse();
    }
}
