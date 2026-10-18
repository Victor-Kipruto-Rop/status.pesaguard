package com.pesaguard.backend.credentials.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class ScopeCodecTest {

    @Test
    void roundTripsScopesInDeterministicOrder() {
        Set<String> scopes = Set.of("webhooks:write", "projects:read");

        String encoded = ScopeCodec.encode(scopes);

        assertThat(encoded).isEqualTo("projects:read,webhooks:write");
        assertThat(ScopeCodec.decode(encoded)).isEqualTo(scopes);
    }
}
