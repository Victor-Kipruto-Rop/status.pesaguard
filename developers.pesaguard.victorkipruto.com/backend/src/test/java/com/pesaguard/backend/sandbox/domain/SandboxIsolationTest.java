package com.pesaguard.backend.sandbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.sandbox.domain.SandboxIsolation.EnvironmentBinding;
import com.pesaguard.backend.sandbox.security.SandboxIsolationViolation;

/**
 * The invariant this phase exists for:
 *
 * <pre>
 *     sandbox  x  production  =  never
 * </pre>
 *
 * <p>These tests are the enforcement. If someone adds a way to obtain a
 * {@code SandboxIsolation} for a production environment, one of these fails.
 */
class SandboxIsolationTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    private SandboxIsolation forType(EnvironmentType type) {
        return SandboxIsolation.forSandboxEnvironment(
                new EnvironmentBinding(environmentId, organizationId, projectId, type));
    }

    @Test
    void aSandboxEnvironmentYieldsAnIsolationToken() {
        SandboxIsolation isolation = forType(EnvironmentType.SANDBOX);

        assertThat(isolation.sandboxEnvironmentId()).isEqualTo(environmentId);
        assertThat(isolation.organizationId()).isEqualTo(organizationId);
        assertThat(isolation.projectId()).isEqualTo(projectId);
    }

    @Test
    void productionIsRefused() {
        // The headline case.
        assertThatThrownBy(() -> forType(EnvironmentType.PRODUCTION))
                .isInstanceOf(SandboxIsolationViolation.class)
                .hasMessageContaining("PRODUCTION");
    }

    @ParameterizedTest
    @EnumSource(value = EnvironmentType.class, names = {"DEVELOPMENT", "STAGING", "PRODUCTION"})
    void everyNonSandboxTierIsRefused(EnvironmentType type) {
        // Not just production. A staging environment holds near-real data, and
        // development may hold a customer's integration credentials; neither is a
        // place a sandbox test may write.
        assertThatThrownBy(() -> forType(type))
                .isInstanceOf(SandboxIsolationViolation.class);
    }

    @Test
    void theProductionCheckIsUnconditional() {
        assertThatThrownBy(() -> SandboxIsolation.requireNotProduction(EnvironmentType.PRODUCTION, "a test call"))
                .isInstanceOf(SandboxIsolationViolation.class)
                .hasMessageContaining("never invoke production");
    }

    @ParameterizedTest
    @EnumSource(value = EnvironmentType.class, names = {"DEVELOPMENT", "SANDBOX", "STAGING"})
    void nonProductionTiersPassTheProductionCheck(EnvironmentType type) {
        assertThatCode(() -> SandboxIsolation.requireNotProduction(type, "a test call"))
                .doesNotThrowAnyException();
    }

    @Test
    void aResourceFromAnotherEnvironmentIsRefused() {
        SandboxIsolation isolation = forType(EnvironmentType.SANDBOX);
        EnvironmentBinding foreign = new EnvironmentBinding(UUID.randomUUID(),
                organizationId, projectId, EnvironmentType.SANDBOX);

        // Another *sandbox* is still the wrong sandbox. Cross-environment access
        // is how sandbox data ends up somewhere it should never be read.
        assertThatThrownBy(() -> isolation.requireSameEnvironment(foreign, "a webhook endpoint"))
                .isInstanceOf(SandboxIsolationViolation.class)
                .hasMessageContaining("webhook endpoint");
    }

    @Test
    void aResourceFromAnotherOrganizationIsRefused() {
        SandboxIsolation isolation = forType(EnvironmentType.SANDBOX);
        EnvironmentBinding foreign = new EnvironmentBinding(environmentId,
                UUID.randomUUID(), projectId, EnvironmentType.SANDBOX);

        assertThatThrownBy(() -> isolation.requireSameEnvironment(foreign, "a credential"))
                .isInstanceOf(SandboxIsolationViolation.class)
                .hasMessageContaining("organization");
    }

    @Test
    void aResourceFromTheSameEnvironmentIsAccepted() {
        SandboxIsolation isolation = forType(EnvironmentType.SANDBOX);
        EnvironmentBinding own = new EnvironmentBinding(environmentId,
                organizationId, projectId, EnvironmentType.SANDBOX);

        assertThatCode(() -> isolation.requireSameEnvironment(own, "a credential"))
                .doesNotThrowAnyException();
    }

    @Test
    void aNullBindingCannotBeSmuggledIn() {
        SandboxIsolation isolation = forType(EnvironmentType.SANDBOX);

        // A null reference reaching here would be an NPE deep in execution rather
        // than a refusal at the boundary.
        assertThatThrownBy(() -> isolation.requireSameEnvironment(null, "a credential"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SandboxIsolation.forSandboxEnvironment(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theIsolationTokenCarriesNoReferenceToAnyProductionEnvironment() {
        // Structural check: the token exposes only its own sandbox identity, so
        // there is nothing on it a caller could redirect at production.
        SandboxIsolation isolation = forType(EnvironmentType.SANDBOX);

        assertThat(isolation.toString()).contains(environmentId.toString());
        assertThat(isolation.toString()).doesNotContain("PRODUCTION");
    }
}