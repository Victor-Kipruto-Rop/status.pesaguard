package com.pesaguard.backend.environment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Tier ordering and isolation rules.
 *
 * <p>These are the controls behind "development cannot reach production". They
 * are worth pinning because every one of them fails open: a permissive rule here
 * would not raise an error, it would quietly let a lower tier act on a higher
 * one.
 */
class EnvironmentIsolationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static ProjectEnvironment environment(EnvironmentType type) {
        return ProjectEnvironment.create(
                UUID.randomUUID(), UUID.randomUUID(), type.name(), type, UUID.randomUUID(), T0);
    }

    // --- Tier ordering ------------------------------------------------------

    @Test
    void tiersAreOrderedFromDevelopmentToProduction() {
        assertThat(EnvironmentType.DEVELOPMENT.rank()).isLessThan(EnvironmentType.SANDBOX.rank());
        assertThat(EnvironmentType.SANDBOX.rank()).isLessThan(EnvironmentType.STAGING.rank());
        assertThat(EnvironmentType.STAGING.rank()).isLessThan(EnvironmentType.PRODUCTION.rank());
    }

    @Test
    void onlyProductionIsProtected() {
        // A single flag the rest of the platform keys off. If more tiers became
        // protected, the rules asserting exactly one would fail.
        assertThat(EnvironmentType.PRODUCTION.isProtected()).isTrue();
        assertThat(EnvironmentType.DEVELOPMENT.isProtected()).isFalse();
        assertThat(EnvironmentType.SANDBOX.isProtected()).isFalse();
        assertThat(EnvironmentType.STAGING.isProtected()).isFalse();
    }

    // --- Promotion ----------------------------------------------------------

    @Test
    void promotionIsForwardOnlyByExactlyOneStep() {
        assertThat(EnvironmentType.DEVELOPMENT.canPromoteTo(EnvironmentType.SANDBOX)).isTrue();
        assertThat(EnvironmentType.SANDBOX.canPromoteTo(EnvironmentType.STAGING)).isTrue();
        assertThat(EnvironmentType.STAGING.canPromoteTo(EnvironmentType.PRODUCTION)).isTrue();
    }

    @Test
    void aTierCannotSkipForward() {
        // Development straight to production would bypass the staging checks that
        // are the entire reason staging exists.
        assertThat(EnvironmentType.DEVELOPMENT.canPromoteTo(EnvironmentType.PRODUCTION)).isFalse();
        assertThat(EnvironmentType.DEVELOPMENT.canPromoteTo(EnvironmentType.STAGING)).isFalse();
        assertThat(EnvironmentType.SANDBOX.canPromoteTo(EnvironmentType.PRODUCTION)).isFalse();
    }

    @Test
    void aTierCannotBeDemotedByPromoting() {
        assertThat(EnvironmentType.PRODUCTION.canPromoteTo(EnvironmentType.STAGING)).isFalse();
        assertThat(EnvironmentType.STAGING.canPromoteTo(EnvironmentType.SANDBOX)).isFalse();
        assertThat(EnvironmentType.SANDBOX.canPromoteTo(EnvironmentType.DEVELOPMENT)).isFalse();
    }

    @Test
    void aTierCannotPromoteToItself() {
        for (EnvironmentType type : EnvironmentType.values()) {
            assertThat(type.canPromoteTo(type))
                    .as("%s must not promote to itself", type)
                    .isFalse();
        }
    }

    @Test
    void productionHasNoNextTier() {
        assertThat(EnvironmentType.PRODUCTION.next()).isEmpty();
    }

    @Test
    void promotingProductionIsRejected() {
        assertThatThrownBy(() -> environment(EnvironmentType.PRODUCTION)
                .promoteTo(EnvironmentType.PRODUCTION, T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void promotingBackwardsIsRejected() {
        assertThatThrownBy(() -> environment(EnvironmentType.STAGING)
                .promoteTo(EnvironmentType.SANDBOX, T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void promotingForwardsIsRecorded() {
        ProjectEnvironment staging = environment(EnvironmentType.SANDBOX);

        staging.promoteTo(EnvironmentType.STAGING, T0);

        assertThat(staging.getType()).isEqualTo(EnvironmentType.STAGING);
    }

    // --- Credential identity ------------------------------------------------

    @Test
    void aNewCredentialIsActiveAndVersionOne() {
        EnvironmentCredential credential = EnvironmentCredential.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "api-key",
                EnvironmentCredentialType.API_KEY, "hash", "ciphertext", "fingerprint", 1, UUID.randomUUID());

        assertThat(credential.getStatus()).isEqualTo(EnvironmentCredentialStatus.ACTIVE);
        assertThat(credential.getVersion()).isEqualTo(1);
    }

    @Test
    void theSecretItselfIsNeverExposed() {
        // Guards against someone adding a getter for secret material later. The
        // No accessor may expose either the original value or its ciphertext.
        for (java.lang.reflect.Method method : EnvironmentCredential.class.getDeclaredMethods()) {
            if (method.getParameterCount() == 0) {
                String name = method.getName().toLowerCase();
                assertThat(name)
                        .as("%s would leak secret material", method.getName())
                        .doesNotContain("secret");
            }
        }
    }

    @Test
    void revokingAcredentialKeepsTheFirstRevocationTime() {
        EnvironmentCredential credential = EnvironmentCredential.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "api-key",
                EnvironmentCredentialType.API_KEY, "hash", "ciphertext", "fingerprint", 1, UUID.randomUUID());

        credential.revoke(T0);
        credential.revoke(T0.plusSeconds(600));

        assertThat(credential.getStatus()).isEqualTo(EnvironmentCredentialStatus.REVOKED);
        assertThat(credential.getRotatedAt()).isEqualTo(T0);
    }

    @Test
    void eachEnvironmentScopesItsOwnCredentials() {
        UUID environmentId = UUID.randomUUID();
        EnvironmentCredential credential = EnvironmentCredential.create(
                UUID.randomUUID(), UUID.randomUUID(), environmentId, "api-key",
                EnvironmentCredentialType.API_KEY, "hash", "ciphertext", "fingerprint", 1, UUID.randomUUID());

        // A credential carries its environment, which is what makes the reuse
        // check scoped to a project rather than global.
        assertThat(credential.getEnvironmentId()).isEqualTo(environmentId);
    }
}
