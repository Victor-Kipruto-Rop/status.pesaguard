package com.pesaguard.backend.rbac.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The production access workflow.
 *
 * <p>The properties that carry the most weight:
 *
 * <ul>
 *   <li>Approval is <b>not</b> activation. An approved request grants nothing, so a
 *       failed provisioning step cannot leave a credential that appears live.</li>
 *   <li>Revocation is <b>irreversible</b>, so whoever revokes cannot quietly undo
 *       their own action.</li>
 *   <li>Separation of duties holds at every step, including claiming a request
 *       for review.</li>
 * </ul>
 */
class ProductionAccessWorkflowTest {

    private static final Instant NOW = Instant.parse("2026-03-15T14:37:52Z");
    private final UUID org = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID environment = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();

    private ProductionAccessRequest request() {
        return ProductionAccessRequest.create(org, project, environment, requester,
                "Going live with the mobile checkout integration", "Mobile checkout",
                "Example merchant and support contact details", "Accept checkout payments and refunds",
                "payments:write, refunds:read", "Up to 1000 requests per minute",
                "Backend service deployed in two regions", "Secrets managed in vault; monitored access",
                NOW);
    }

    private ProductionAccessRequest approved() {
        ProductionAccessRequest request = request();
        request.approve(reviewer, "Reviewed, sandbox evidence attached",
                NOW.plus(Duration.ofHours(24)), NOW);
        return request;
    }

    private ProductionAccessRequest active() {
        ProductionAccessRequest request = approved();
        request.activate(reviewer, NOW.plusSeconds(60));
        return request;
    }

    @Test
    void aNewRequestIsPendingAndGrantsNothing() {
        assertThat(request().getStatus()).isEqualTo(ProductionAccessStatus.PENDING);
        assertThat(request().isActiveGrant(NOW)).isFalse();
    }

    @Test
    void aReviewerCanClaimARequest() {
        ProductionAccessRequest request = request();

        request.beginReview(reviewer, NOW);

        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.UNDER_REVIEW);
        assertThat(request.getReviewedBy()).isEqualTo(reviewer);
    }

    @Test
    void theRequesterCannotReviewTheirOwnRequest() {
        assertThatThrownBy(() -> request().beginReview(requester, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request().approve(requester, "self approve",
                        NOW.plus(Duration.ofHours(1)), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void approvalRecordsADecisionButGrantsNothing() {
        // The point of separating APPROVED from ACTIVE: provisioning has not run yet.
        ProductionAccessRequest request = approved();

        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.APPROVED);
        assertThat(request.isActiveGrant(NOW)).isFalse();
        assertThat(request.getStatus().permitsTraffic()).isFalse();
    }

    @Test
    void activationBringsAnApprovedGrantLive() {
        ProductionAccessRequest request = approved();

        request.activate(reviewer, NOW.plusSeconds(60));

        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.ACTIVE);
        assertThat(request.isActiveGrant(NOW.plusSeconds(60))).isTrue();
        assertThat(request.getActivatedBy()).isEqualTo(reviewer);
        assertThat(request.getActivatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void onlyAnApprovedRequestCanBeActivated() {
        assertThatThrownBy(() -> request().activate(reviewer, NOW))
                .isInstanceOf(IllegalStateException.class);
        // Already active: activating twice must not silently succeed.
        assertThatThrownBy(() -> active().activate(reviewer, NOW.plusSeconds(90)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activatingAfterTheWindowElapsedFailsAndExpires() {
        // A request approved for 24h and activated on day 4 must not come up
        // already-live; the elapsed window has to be visible.
        ProductionAccessRequest request = approved();

        assertThatThrownBy(() -> request.activate(reviewer, NOW.plus(Duration.ofDays(4))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("elapsed");
        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.EXPIRED);
    }

    @Test
    void suspensionStopsTrafficAndRequiresAReason() {
        ProductionAccessRequest request = active();

        request.suspend(reviewer, "Credential leaked in a public gist", NOW.plusSeconds(120));

        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.SUSPENDED);
        assertThat(request.isActiveGrant(NOW.plusSeconds(120))).isFalse();
        assertThat(request.getSuspensionReason()).contains("leaked");
    }

    @Test
    void suspensionWithoutAReasonIsRefused() {
        // An unexplained stop is unactionable for whoever has to investigate it.
        assertThatThrownBy(() -> active().suspend(reviewer, "  ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSuspendedGrantCanReturnToService() {
        ProductionAccessRequest request = active();
        request.suspend(reviewer, "Under investigation", NOW.plusSeconds(120));

        request.reactivate(reviewer, NOW.plusSeconds(300));

        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.ACTIVE);
        assertThat(request.getSuspensionReason()).isNull();
    }

    @Test
    void resumptionDoesNotExtendTheOriginalWindow() {
        // Otherwise suspension becomes a way to grant extra time indefinitely.
        ProductionAccessRequest request = approved();
        request.activate(reviewer, NOW.plusSeconds(60));
        request.suspend(reviewer, "Under investigation", NOW.plusSeconds(120));

        // Beyond the original 24h expiry.
        Instant later = NOW.plus(Duration.ofDays(2));
        assertThatThrownBy(() -> request.reactivate(reviewer, later))
                .isInstanceOf(IllegalStateException.class);
        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.EXPIRED);
    }

    @Test
    void revocationIsTerminal() {
        // Whoever revokes must not be able to quietly undo it.
        ProductionAccessRequest request = active();

        request.revoke(reviewer, "Integration abandoned", NOW.plusSeconds(200));

        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.REVOKED);
        assertThat(request.isActiveGrant(NOW.plusSeconds(200))).isFalse();
        assertThat(request.getStatus().isTerminal()).isTrue();

        // There is no transition out of REVOKED.
        assertThatThrownBy(() -> request.reactivate(reviewer, NOW.plusSeconds(300)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.activate(reviewer, NOW.plusSeconds(300)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.beginReview(reviewer, NOW.plusSeconds(300)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokingTwiceIsRefused() {
        ProductionAccessRequest request = active();
        request.revoke(reviewer, "Integration abandoned", NOW);

        assertThatThrownBy(() -> request.revoke(reviewer, "changed my mind", NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revocationRequiresAReason() {
        assertThatThrownBy(() -> active().revoke(reviewer, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anElapsedGrantExpiresRatherThanStayingLive() {
        ProductionAccessRequest request = active();
        Instant after = NOW.plus(Duration.ofDays(2));

        assertThat(request.isActiveGrant(after)).isFalse();
        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.EXPIRED);
    }

    @Test
    void aSuspendedGrantAlsoExpiresOnceItsWindowPasses() {
        // Suspended within the window, then read after it elapses. Suspension must
        // not pin a grant open indefinitely.
        ProductionAccessRequest request = active();
        request.suspend(reviewer, "Under investigation", NOW.plusSeconds(120));

        // Past the 24h window the suspended grant lapses too.
        request.expireIfElapsed(NOW.plus(Duration.ofDays(2)));

        assertThat(request.getStatus()).isEqualTo(ProductionAccessStatus.EXPIRED);
    }


    @Test
    void aReasonlessRequestIsRefusedAtCreation() {
        assertThatThrownBy(() -> ProductionAccessRequest.create(org, project, environment,
                requester, "   ", "Mobile checkout", "Example merchant details",
                "Accept checkout payments", "payments:write", "1000 per minute",
                "Backend service architecture", "Secrets stored securely", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyActivePermitsTraffic() {
        assertThat(ProductionAccessStatus.ACTIVE.permitsTraffic()).isTrue();
        assertThat(ProductionAccessStatus.APPROVED.permitsTraffic()).isFalse();
        assertThat(ProductionAccessStatus.UNDER_REVIEW.permitsTraffic()).isFalse();
        assertThat(ProductionAccessStatus.SUSPENDED.permitsTraffic()).isFalse();
        assertThat(ProductionAccessStatus.REVOKED.permitsTraffic()).isFalse();
    }
}
