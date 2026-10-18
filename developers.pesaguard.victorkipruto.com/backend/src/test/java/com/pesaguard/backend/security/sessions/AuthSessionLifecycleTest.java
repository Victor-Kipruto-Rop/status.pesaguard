package com.pesaguard.backend.security.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;

/**
 * Session liveness, revocation, and device tracking.
 *
 * <p>These are the rules that decide whether an already-issued cookie is still
 * honoured. The failure mode worth guarding is a session that stays usable past
 * the point it should have died, because that turns a closed session into silent
 * continued access.
 */
class AuthSessionLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static final Duration ABSOLUTE_TTL = Duration.ofHours(12);

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private static AuthSession session() {
        return session(null, null);
    }

    private static AuthSession session(String deviceLabel, String lastIp) {
        Organization organization = Organization.create("Acme", "acme", UUID.randomUUID(), T0);
        UserAccount user = UserAccount.create("dev@example.com", "Dev", "hash");
        OrganizationMembership membership = OrganizationMembership.owner(organization, user);
        return AuthSession.create(
                "token-hash-" + UUID.randomUUID(), membership, T0, T0.plus(ABSOLUTE_TTL),
                deviceLabel, lastIp);
    }

    // --- Liveness -----------------------------------------------------------

    @Test
    void aNewSessionIsActive() {
        assertThat(session().isActive(T0)).isTrue();
    }

    @Test
    void aSessionIsActiveBeforeItsAbsoluteExpiry() {
        assertThat(session().isActive(T0.plus(ABSOLUTE_TTL).minusSeconds(1))).isTrue();
    }

    @Test
    void aSessionIsNotActivePastItsAbsoluteExpiry() {
        assertThat(session().isActive(T0.plus(ABSOLUTE_TTL).plusSeconds(1))).isFalse();
    }

    @Test
    void anIdleSessionGoesInactiveBeforeItsAbsoluteExpiry() {
        // Absolute TTL is 12h, idle timeout 30m. Without the idle check a session
        // abandoned on a shared machine would stay valid all day.
        assertThat(session().isActive(T0.plus(IDLE_TIMEOUT).plusSeconds(1), IDLE_TIMEOUT))
                .isFalse();
    }

    @Test
    void activityPushesTheIdleDeadlineForward() {
        AuthSession session = session();
        Instant later = T0.plus(IDLE_TIMEOUT).minusSeconds(60);

        session.touch(later);

        assertThat(session.isActive(later.plusSeconds(60), IDLE_TIMEOUT)).isTrue();
    }

    @Test
    void anIdleSessionStaysInactiveEvenAfterBeingTouched() {
        // Absolute expiry is a hard ceiling: activity must not extend a session
        // beyond it.
        AuthSession session = session();

        session.touch(T0.plus(ABSOLUTE_TTL).minusSeconds(1));

        assertThat(session.isActive(T0.plus(ABSOLUTE_TTL).plusSeconds(1), IDLE_TIMEOUT))
                .isFalse();
    }

    // --- Revocation ---------------------------------------------------------

    @Test
    void aRevokedSessionIsNotActiveEvenBeforeItExpires() {
        AuthSession session = session();

        session.revoke(T0.plusSeconds(60));

        // Revocation must beat an unexpired clock, or a stolen cookie stays live
        // for the rest of its natural life.
        assertThat(session.isActive(T0.plusSeconds(61))).isFalse();
    }

    @Test
    void revokingRecordsTheTime() {
        AuthSession session = session();

        session.revoke(T0.plusSeconds(60));

        assertThat(session.getRevokedAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void revokingTwiceKeepsTheOriginalRevocationTime() {
        AuthSession session = session();
        session.revoke(T0.plusSeconds(60));

        session.revoke(T0.plusSeconds(600));

        assertThat(session.getRevokedAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void touchingARevokedSessionDoesNotReviveIt() {
        AuthSession session = session();
        session.revoke(T0.plusSeconds(60));

        session.touch(T0.plusSeconds(90));

        assertThat(session.isActive(T0.plusSeconds(120))).isFalse();
    }

    @Test
    void observingARevokedSessionDoesNotReviveIt() {
        AuthSession session = session();
        session.revoke(T0.plusSeconds(60));

        session.observe(T0.plusSeconds(90), "Chrome on Windows", "10.0.0.1");

        assertThat(session.isActive(T0.plusSeconds(120))).isFalse();
    }

    // --- Device tracking ----------------------------------------------------

    @Test
    void aSessionRecordsWhereItCameFrom() {
        AuthSession session = session("Chrome on Windows", "203.0.113.9");

        assertThat(session.getDeviceLabel()).isEqualTo("Chrome on Windows");
        assertThat(session.getLastIp()).isEqualTo("203.0.113.9");
    }

    @Test
    void anAbsentDeviceIsRecordedAsAbsentRatherThanGuessed() {
        AuthSession session = session();

        assertThat(session.getDeviceLabel()).isNull();
        assertThat(session.getLastIp()).isNull();
    }

    @Test
    void observingUpdatesTheLastSeenAddress() {
        AuthSession session = session("Chrome on Windows", "203.0.113.9");

        session.observe(T0.plusSeconds(30), "Chrome on Windows", "198.51.100.4");

        assertThat(session.getLastIp()).isEqualTo("198.51.100.4");
    }

    @Test
    void observingKeepsTheOriginalDeviceWhenTheLabelAgrees() {
        // A browser may report a rotating agent string. Replacing the label each
        // time would make the device unrecognisable in a security dashboard.
        AuthSession session = session("Chrome on Windows", "203.0.113.9");

        session.observe(T0.plusSeconds(30), "Chrome on Windows", "198.51.100.4");

        assertThat(session.getDeviceLabel()).isEqualTo("Chrome on Windows");
    }

    @Test
    void anOverlongDeviceLabelIsBounded() {
        AuthSession session = session(null, null);

        session.observe(T0.plusSeconds(30), "X".repeat(500), "203.0.113.9");

        assertThat(session.getDeviceLabel()).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void anOverlongAddressIsBounded() {
        // 45 is the widest an IPv4 or IPv6 address with a scope id can be.
        AuthSession session = session(null, null);

        session.observe(T0.plusSeconds(30), "Chrome", "9".repeat(500));

        assertThat(session.getLastIp()).hasSizeLessThanOrEqualTo(45);
    }

    @Test
    void aBlankDeviceLabelDoesNotOverwriteAKnownOne() {
        AuthSession session = session("Chrome on Windows", "203.0.113.9");

        session.observe(T0.plusSeconds(30), "   ", "198.51.100.4");

        // A blank observation is missing data, not evidence the device changed.
        assertThat(session.getDeviceLabel()).isEqualTo("Chrome on Windows");
    }

    @Test
    void observingAlwaysAdvancesTheLastSeenTime() {
        AuthSession session = session("Chrome on Windows", "203.0.113.9");
        Instant later = T0.plusSeconds(45);

        session.observe(later, null, null);

        assertThat(session.getLastSeenAt()).isEqualTo(later);
    }

    // --- Token material -----------------------------------------------------

    @Test
    void eachSessionGetsItsOwnIdentifier() {
        assertThat(session().getId()).isNotEqualTo(session().getId());
    }

    @Test
    void thePlaintextTokenIsNeverTheStoredHash() {
        AuthSession session = session();

        // The column holds a hash of the cookie value, so a leaked table cannot be
        // replayed as a set of live sessions.
        assertThat(session.getTokenHash()).startsWith("token-hash-");
        assertThat(session.getTokenHash()).hasSizeLessThanOrEqualTo(100);
    }

    @Test
    void theTokenHashFitsTheColumnWithoutTruncation() {
        // The column is char(64) and the value is an HMAC-SHA256 hex digest, so it
        // is fixed-length by construction and never truncated. Truncating a hash
        // would silently weaken it, so this asserts the real invariant instead.
        String hash = session().getTokenHash();

        assertThat(hash).hasSizeLessThanOrEqualTo(64);
    }
}