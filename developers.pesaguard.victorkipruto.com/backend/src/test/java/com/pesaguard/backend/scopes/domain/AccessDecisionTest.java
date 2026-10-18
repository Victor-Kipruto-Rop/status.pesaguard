package com.pesaguard.backend.scopes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The decision builder must fail closed and explain itself.
 *
 * <p>Two behaviours are pinned here because they are what make a denial
 * trustworthy: the first failure wins (so a reason code is never misleading), and
 * evaluation genuinely stops (so a trace cannot claim factors passed that were
 * never checked).
 */
class AccessDecisionTest {

    private static final UUID ORG = UUID.randomUUID();

    @Test
    void anEmptyBuilderDenies() {
        // A decision that evaluated nothing has not been shown to be safe. Without
        // this, a caller who forgot to record a factor would silently get ALLOWED.
        AccessDecision decision = new AccessDecision.Builder().build();

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.NO_FACTORS_EVALUATED);
    }

    @Test
    void allFactorsPassingAllows() {
        AccessDecision decision = new AccessDecision.Builder()
                .record("IDENTITY_UNAUTHENTICATED", true, "user")
                .record("ORGANIZATION_UNAVAILABLE", true, ORG.toString())
                .record("ALLOWED", true, "all factors passed")
                .build();

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(AccessDecision.ReasonCode.ALLOWED);
        assertThat(decision.factorTrace()).contains("IDENTITY_UNAUTHENTICATED=PASS(user)");
    }

    @Test
    void theFirstFailureIsTheReportedReason() {
        AccessDecision decision = new AccessDecision.Builder()
                .record("IDENTITY_UNAUTHENTICATED", false, "no principal")
                .build();

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.IDENTITY_UNAUTHENTICATED);
    }

    @Test
    void evaluationStopsAtTheFirstFailure() {
        // A later factor claiming to pass after an earlier denial would make the
        // trace read as "only one thing was wrong", which is not what happened.
        AccessDecision decision = new AccessDecision.Builder()
                .record("IDENTITY_UNAUTHENTICATED", false, "no principal")
                .record("ORGANIZATION_UNAVAILABLE", true, "org")
                .record("ALLOWED", true, "all factors passed")
                .build();

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.factorTrace()).doesNotContain("ORGANIZATION_UNAVAILABLE");
        assertThat(decision.factorTrace()).doesNotContain("ALLOWED");
    }

    @Test
    void aLaterFailureDoesNotOverrideTheFirstReason() {
        AccessDecision decision = new AccessDecision.Builder()
                .record("IDENTITY_UNAUTHENTICATED", true, "user")
                .record("CREDENTIAL_STATUS_BLOCKED", false, "REVOKED")
                .record("SCOPE_NOT_ASSIGNED", false, "fraud:read")
                .build();

        assertThat(decision.reasonCode())
                .isEqualTo(AccessDecision.ReasonCode.CREDENTIAL_STATUS_BLOCKED);
    }

    @Test
    void contextAndRequestAreCarriedOntoTheDecision() {
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        ApiScope scope = ApiScope.tryParse("payments:read").orElseThrow();

        AccessDecision decision = new AccessDecision.Builder()
                .record("ALLOWED", true, "ok")
                .context(ORG, user, key, project, environment, scope)
                .request("req-1", "203.0.113.9")
                .build();

        assertThat(decision.organizationId()).isEqualTo(ORG);
        assertThat(decision.userId()).isEqualTo(user);
        assertThat(decision.apiKeyId()).isEqualTo(key);
        assertThat(decision.projectId()).isEqualTo(project);
        assertThat(decision.environmentId()).isEqualTo(environment);
        assertThat(decision.requestedScope()).isEqualTo(scope);
        assertThat(decision.requestId()).isEqualTo("req-1");
        assertThat(decision.remoteAddress()).isEqualTo("203.0.113.9");
    }

    @Test
    void anUnknownFactorNameIsRejectedRatherThanSilentlyIgnored() {
        // A typo'd reason code would produce a decision with a null failure reason,
        // which is far harder to diagnose than a build-time failure here.
        assertThatThrownBy(() -> new AccessDecision.Builder()
                .record("SCOPE_NOT_ASSIGNEDD", false, "typo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyReasonCodeHasADistinctValue() {
        assertThat(java.util.Arrays.stream(AccessDecision.ReasonCode.values())
                .map(AccessDecision.ReasonCode::value).distinct().count())
                .isEqualTo(AccessDecision.ReasonCode.values().length);
    }
}