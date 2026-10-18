package com.pesaguard.backend.support.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {

    @Test
    void removesCredentialHeadersBearerTokensAndPesaGuardSecrets() {
        String text = """
                Authorization: Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature12345
                X-API-Key: pg_live_1234567890abcdef
                webhook whsec_1234567890abcdef
                """;

        String result = SensitiveDataRedactor.redact(text);

        assertThat(result).contains("Authorization: [REDACTED]");
        assertThat(result).contains("X-API-Key: [REDACTED]");
        assertThat(result).contains("webhook [REDACTED]");
        assertThat(result).doesNotContain("pg_live_1234567890abcdef");
        assertThat(result).doesNotContain("eyJhbGci");
    }

    @Test
    void preservesOrdinaryTroubleshootingText() {
        assertThat(SensitiveDataRedactor.redact("Request req_abc123 returned HTTP 401."))
                .isEqualTo("Request req_abc123 returned HTTP 401.");
    }
}
