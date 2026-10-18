package com.pesaguard.backend.scopes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Scope values are matched exactly. The grammar is the first line of defence:
 * a scope that cannot be parsed is never granted, so a malformed value cannot
 * reach the decision engine at all.
 */
class ApiScopeTest {

    @Test
    void parsesResourceAndAction() {
        ApiScope scope = ApiScope.tryParse("transactions:read").orElseThrow();

        assertThat(scope.resource()).isEqualTo("transactions");
        assertThat(scope.action()).isEqualTo("read");
        assertThat(scope.value()).isEqualTo("transactions:read");
    }

    @Test
    void rejectsMalformedValues() {
        assertThat(ApiScope.tryParse("transactions")).isEmpty();
        assertThat(ApiScope.tryParse("transactions:")).isEmpty();
        assertThat(ApiScope.tryParse(":read")).isEmpty();
        assertThat(ApiScope.tryParse("transactions:read:extra")).isEmpty();
        assertThat(ApiScope.tryParse("Transactions:Read")).isEmpty();
        assertThat(ApiScope.tryParse("transactions:read ")).isEmpty();
        assertThat(ApiScope.tryParse(null)).isEmpty();
    }

    @Test
    void uppercaseIsNotSilentlyFolded() {
        // A scope is matched by exact value. Accepting an uppercase spelling would
        // mean two strings refer to one grant, and the stored value would no longer
        // be what the caller asked for.
        assertThat(ApiScope.tryParse("WEBHOOKS:READ")).isEmpty();
    }

    @Test
    void distinguishesReadFromWrite() {
        assertThat(ApiScope.tryParse("payments:read").orElseThrow().isWrite()).isFalse();
        assertThat(ApiScope.tryParse("payments:write").orElseThrow().isWrite()).isTrue();
    }

    @Test
    void sortingIsByCanonicalValue() {
        assertThat(java.util.stream.Stream.of(
                        ApiScope.tryParse("payments:read").orElseThrow(),
                        ApiScope.tryParse("fraud:read").orElseThrow(),
                        ApiScope.tryParse("transactions:read").orElseThrow())
                .sorted().map(ApiScope::value).toList())
                .containsExactly("fraud:read", "payments:read", "transactions:read");
    }
}