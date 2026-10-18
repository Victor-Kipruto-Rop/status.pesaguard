package com.pesaguard.backend.platformadmin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Operator authorization rules.
 *
 * <p>Two properties matter. An operator is not scoped to a tenant, so there is no
 * organization to accidentally filter by; and every action affecting a customer
 * requires a stated reason, because the customer is not in the room.
 */
class AuthenticatedOperatorTest {

    private AuthenticatedOperator operator(Set<OperatorCapability> capabilities, String reason) {
        return new AuthenticatedOperator(UUID.randomUUID(), "ops@example.com", capabilities,
                reason);
    }

    @Test
    void anOperatorCarriesNoOrganization() {
        // Deliberately absent: an operator is not tenant-scoped, and an
        // organizationId field would invite code that filters by it and quietly
        // returns the wrong customer's data.
        assertThat(AuthenticatedOperator.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("organizationId");
    }

    @Test
    void capabilitiesAreDefensivelyCopied() {
        EnumSet<OperatorCapability> mutable = EnumSet.of(OperatorCapability.USAGE_READ);
        AuthenticatedOperator operator = new AuthenticatedOperator(UUID.randomUUID(), "ops",
                mutable, null);

        // Widening the caller's set afterwards must not widen the operator.
        mutable.add(OperatorCapability.CREDENTIALS_REVOKE);

        assertThat(operator.can(OperatorCapability.CREDENTIALS_REVOKE)).isFalse();
    }

    @Test
    void aReadOnlyOperatorCannotPerformAMutatingAction() {
        AuthenticatedOperator reader = operator(Set.of(OperatorCapability.CREDENTIALS_READ),
                "support ticket 1234");

        assertThat(reader.can(OperatorCapability.CREDENTIALS_READ)).isTrue();
        assertThatThrownBy(() -> reader.require(OperatorCapability.CREDENTIALS_REVOKE))
                .isInstanceOf(AuthenticatedOperator.OperatorAuthorizationException.class);
    }

    @Test
    void aReasonIsRequiredForAnActionAffectingACustomer() {
        // A revoke with no recorded reason cannot be explained to the customer
        // afterwards, and the audit trail would say only that someone with platform
        // access ended a working integration.
        AuthenticatedOperator operator = operator(Set.of(OperatorCapability.CREDENTIALS_REVOKE),
                null);

        assertThatThrownBy(operator::requireReason)
                .isInstanceOf(AuthenticatedOperator.OperatorAuthorizationException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void aBlankOrControlOnlyReasonIsRefused() {
        assertThatThrownBy(() -> operator(Set.of(), "   ").requireReason())
                .isInstanceOf(AuthenticatedOperator.OperatorAuthorizationException.class);
        assertThatThrownBy(() -> operator(Set.of(), "\n\t").requireReason())
                .isInstanceOf(AuthenticatedOperator.OperatorAuthorizationException.class);
    }

    @Test
    void aReasonIsSanitisedAndBounded() {
        AuthenticatedOperator operator = operator(Set.of(),
                "support ticket 1234\nsecond line");

        String reason = operator.requireReason();

        // Newlines would corrupt a single-line audit entry.
        assertThat(reason).doesNotContain("\n");
        assertThat(reason).contains("support ticket 1234");
    }

    @Test
    void anOverlongReasonIsRefused() {
        assertThatThrownBy(() -> operator(Set.of(), "x".repeat(501)).requireReason())
                .isInstanceOf(AuthenticatedOperator.OperatorAuthorizationException.class);
    }

    @Test
    void readAndMutatingCapabilitiesAreSeparated() {
        // Answering a support question must not require the power to end an
        // integration.
        assertThat(OperatorCapability.CREDENTIALS_READ.isReadOnly()).isTrue();
        assertThat(OperatorCapability.USAGE_READ.isReadOnly()).isTrue();
        assertThat(OperatorCapability.SECURITY_READ.isReadOnly()).isTrue();

        assertThat(OperatorCapability.CREDENTIALS_REVOKE.isMutating()).isTrue();
        assertThat(OperatorCapability.CREDENTIALS_SUSPEND.isMutating()).isTrue();
        assertThat(OperatorCapability.PRODUCTION_REVIEW.isMutating()).isTrue();
        assertThat(OperatorCapability.SECURITY_RESOLVE.isMutating()).isTrue();
    }

    @Test
    void reviewingIsDistinctFromReadingAProductionRequest() {
        // Otherwise every read-only operator could approve a production grant.
        assertThat(OperatorCapability.PRODUCTION_READ.isMutating()).isFalse();
        assertThat(OperatorCapability.PRODUCTION_REVIEW.isMutating()).isTrue();
    }

    @Test
    void resolvingASecuritySignalIsDistinctFromReadingOne() {
        // Same reasoning as Phase 15: a detector must never close its own findings,
        // and neither should a read-only operator.
        assertThat(OperatorCapability.SECURITY_READ.isMutating()).isFalse();
        assertThat(OperatorCapability.SECURITY_RESOLVE.isMutating()).isTrue();
    }

    @Test
    void everyMutatingCapabilityIsOneACustomerWouldNotice() {
        // Nothing here is "housekeeping": each of these stops or changes something
        // a developer can see, which is why each needs a stated reason.
        for (OperatorCapability capability : OperatorCapability.values()) {
            if (capability.isMutating()) {
                assertThat(capability.isReadOnly()).isFalse();
            }
        }
    }
}