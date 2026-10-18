package com.pesaguard.backend.securitycenter.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The rules governing security signals.
 *
 * <p>The central property is that a signal is an observation and only a person
 * may resolve one. Everything else follows from keeping that distinction honest:
 * no severity ranking, no auto-resolution, and a scope, not a verdict.
 */
class SecurityEventTest {

    private static final Instant T = Instant.parse("2026-03-15T14:37:52Z");
    private final UUID org = UUID.randomUUID();

    @Test
    void aDetectedEventIsAlwaysOpen() {
        // Detection must never be able to close its own finding, or its silence
        // becomes indistinguishable from "nothing is wrong".
        SecurityEvent.Record event = SecurityEvent.Record.detected(org,
                SecurityEventType.REVOKED_CREDENTIAL_USAGE, UUID.randomUUID(), "API_KEY",
                "key presented after revocation", T);

        assertThat(event.resolution()).isEqualTo(SecurityEvent.Resolution.OPEN);
        assertThat(event.isOpen()).isTrue();
        assertThat(event.isClosed()).isFalse();
        assertThat(event.resolvedBy()).isNull();
        assertThat(event.resolvedAt()).isNull();
    }

    @Test
    void anEventWithoutATenantCannotBeConstructed() {
        // Defence in depth: a signal with no organization could surface in the
        // wrong tenant's list.
        assertThatThrownBy(() -> SecurityEvent.Record.detected(null,
                SecurityEventType.ABNORMAL_API_USAGE, null, "API_KEY", "x", T))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEventWithoutATypeCannotBeConstructed() {
        assertThatThrownBy(() -> SecurityEvent.Record.detected(org, null, null, "API_KEY",
                "x", T))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyDismissedAndConfirmedCountAsClosed() {
        // An investigation still running must not be reported as resolved, or a
        // long-running incident quietly drops off the dashboard.
        assertThat(closed(SecurityEvent.Resolution.DISMISSED)).isTrue();
        assertThat(closed(SecurityEvent.Resolution.CONFIRMED)).isTrue();
        assertThat(closed(SecurityEvent.Resolution.INVESTIGATING)).isFalse();
        assertThat(closed(SecurityEvent.Resolution.OPEN)).isFalse();
    }

    private boolean closed(SecurityEvent.Resolution resolution) {
        return new SecurityEvent.Record(UUID.randomUUID(), org,
                SecurityEventType.SCOPE_ABUSE, null, "API_KEY", null, T, resolution,
                UUID.randomUUID(), T, "looked at it").isClosed();
    }

    @Test
    void investigatingIsNotOpenButAlsoNotClosed() {
        SecurityEvent.Record event = new SecurityEvent.Record(UUID.randomUUID(), org,
                SecurityEventType.AUTHORIZATION_FAILURE, null, "API_KEY", null, T,
                SecurityEvent.Resolution.INVESTIGATING, UUID.randomUUID(), T, "checking");

        assertThat(event.isOpen()).isFalse();
        assertThat(event.isClosed()).isFalse();
    }

    @Test
    void onlySeriousCategoriesAreFlaggedAsSuch() {
        // A containment failure is categorically different from a hygiene signal,
        // and conflating them would train developers to ignore the whole list.
        assertThat(SecurityEventType.AUTHORIZATION_FAILURE.isSerious()).isTrue();
        assertThat(SecurityEventType.REVOKED_CREDENTIAL_USAGE.isSerious()).isTrue();
        assertThat(SecurityEventType.TOKEN_REPLAY.isSerious()).isTrue();

        assertThat(SecurityEventType.SCOPE_ABUSE.isSerious()).isFalse();
        assertThat(SecurityEventType.ALLOWLIST_VIOLATION.isSerious()).isFalse();
        assertThat(SecurityEventType.ABNORMAL_API_USAGE.isSerious()).isFalse();
    }

    @Test
    void commonlyBenignSignalsAreStillReported() {
        // Used to prioritise, never to suppress. A suppressed signal is one nobody
        // can investigate later.
        assertThat(SecurityEventType.SCOPE_ABUSE.isCommonlyBenign()).isTrue();
        assertThat(SecurityEventType.REPEATED_FAILURES.isCommonlyBenign()).isTrue();
        assertThat(SecurityEventType.AUTHORIZATION_FAILURE.isCommonlyBenign()).isFalse();
    }
}