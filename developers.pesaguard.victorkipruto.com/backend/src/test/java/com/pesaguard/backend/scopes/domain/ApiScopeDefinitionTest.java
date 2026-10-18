package com.pesaguard.backend.scopes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Deprecation and restriction semantics.
 *
 * <p>The behaviour worth protecting: deprecating a scope does not stop it
 * working, and a scope cannot be recorded as replaced by itself.
 */
class ApiScopeDefinitionTest {

    private ApiScopeDefinition definition() {
        return new ApiScopeDefinition("payments:read", "Read payments.", "payments",
                "payments", "read", 1, false);
    }

    private ApiScopeDefinition writeScope() {
        return new ApiScopeDefinition("payments:write", "Move money.", "payments",
                "payments", "write", 1, true);
    }

    @Test
    void aFreshScopeIsNeitherDeprecatedNorRestricted() {
        ApiScopeDefinition scope = definition();

        assertThat(scope.isDeprecated()).isFalse();
        assertThat(scope.isRestricted()).isFalse();
        assertThat(scope.getReplacedBy()).isNull();
        assertThat(scope.getVersion()).isEqualTo(1);
    }

    @Test
    void deprecationRecordsTheReplacementAndReason() {
        ApiScopeDefinition scope = definition();
        scope.deprecate("payments:write", "replaced by a finer split");

        assertThat(scope.isDeprecated()).isTrue();
        assertThat(scope.getReplacedBy()).isEqualTo("payments:write");
        assertThat(scope.getChangeReason()).isEqualTo("replaced by a finer split");
    }

    @Test
    void deprecationDoesNotBreakTheScope() {
        // A deprecated scope must keep authenticating. Cutting it off would break
        // every integration that has not migrated, which is an outage rather than
        // a deprecation.
        ApiScopeDefinition scope = definition();
        scope.deprecate("payments:write", "moving on");

        assertThat(scope.isUsable()).isTrue();
        assertThat(scope.scope().value()).isEqualTo("payments:read");
    }

    @Test
    void aScopeCannotReplaceItself() {
        ApiScopeDefinition scope = definition();

        assertThatThrownBy(() -> scope.deprecate("payments:read", "nonsense"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restrictionRequiresTheScopeToBecomeRestricted() {
        ApiScopeDefinition scope = definition();
        scope.restrict("grants access to payment data");

        assertThat(scope.isRestricted()).isTrue();
        assertThat(scope.getChangeReason()).isEqualTo("grants access to payment data");
    }

    @Test
    void bumpingTheVersionRecordsWhyItChanged() {
        ApiScopeDefinition scope = definition();
        scope.bumpVersion("scope now covers refunds too");

        assertThat(scope.getVersion()).isEqualTo(2);
        assertThat(scope.getChangeReason()).isEqualTo("scope now covers refunds too");
    }

    @Test
    void scopeParsesBackToItsCanonicalValue() {
        assertThat(definition().scope())
                .isEqualTo(new ApiScope("payments", "read"));
        assertThat(writeScope().scope().isWrite()).isTrue();
    }
}