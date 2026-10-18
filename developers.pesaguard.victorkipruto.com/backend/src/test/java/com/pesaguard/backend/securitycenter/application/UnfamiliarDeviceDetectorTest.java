package com.pesaguard.backend.securitycenter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;

import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.sessions.AuthSession;
import com.pesaguard.backend.security.sessions.AuthSessionRepository;
import com.pesaguard.backend.securitycenter.domain.SecurityEventType;

/**
 * The unfamiliar-device signal.
 *
 * <p>Most of what is asserted here is about restraint. A detector that flags
 * every sign-in, or that can fail a login, is worse than no detector: the first
 * trains developers to ignore the list, and the second is an outage.
 */
class UnfamiliarDeviceDetectorTest {

    private static final Instant NOW = Instant.parse("2026-05-04T09:00:00Z");

    private final AuthSessionRepository sessionRepository = mock(AuthSessionRepository.class);
    private final SecurityEventService securityEventService = mock(SecurityEventService.class);

    private UnfamiliarDeviceDetector detector;
    private AuthenticatedUser principal;
    private UUID currentSessionId;

    @BeforeEach
    void setUp() {
        detector = new UnfamiliarDeviceDetector(sessionRepository, securityEventService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        principal = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "person@example.com", "Person", Set.of("ROLE_OWNER"), OrganizationStatus.ACTIVE);
        currentSessionId = UUID.randomUUID();
    }

    /** A live session belonging to this user, as the repository would return it. */
    private AuthSession existingSession(String deviceLabel) {
        UserAccount user = UserAccount.create("person@example.com", "Person", "encoded-hash");
        Organization organization = Organization.create("Acme", "acme-" + UUID.randomUUID(),
                user.getId(), NOW);
        return AuthSession.create("hash-" + UUID.randomUUID(),
                OrganizationMembership.owner(organization, user),
                NOW, NOW.plusSeconds(3600), deviceLabel, "203.0.113.10");
    }

    private void givenExistingSessions(List<AuthSession> sessions) {
        when(sessionRepository.findActiveByUserIdOrderByLastSeenAtDesc(
                eq(principal.userId()), any())).thenReturn(sessions);
    }

    @Test
    void aDeviceTheAccountHasUsedBeforeIsNotFlagged() {
        givenExistingSessions(List.of(existingSession("Chrome on Windows")));

        assertThat(detector.evaluate(principal, "Chrome on Windows", currentSessionId)).isFalse();

        verify(securityEventService, never()).record(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void aDeviceTheAccountHasNeverUsedIsFlagged() {
        givenExistingSessions(List.of(existingSession("Chrome on Windows")));

        assertThat(detector.evaluate(principal, "Safari on iOS", currentSessionId)).isTrue();

        verify(securityEventService).record(eq(principal.organizationId()),
                eq(SecurityEventType.UNFAMILIAR_DEVICE_SIGNIN),
                eq(principal.userId()), eq("user"), anyString());
    }

    @Test
    void aFirstEverSessionIsNeverFlagged() {
        // Every registration would otherwise produce a signal, and a list where
        // every signup appears teaches developers to dismiss the list.
        givenExistingSessions(List.of());

        assertThat(detector.evaluate(principal, "Chrome on Windows", currentSessionId)).isFalse();

        verify(securityEventService, never()).record(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void theSessionJustCreatedCannotVouchForItself() {
        // The new session is already in the repository by the time this runs.
        // Comparing without excluding it would let the first sign-in from a
        // device match itself, and the signal would never fire.
        AuthSession justCreated = existingSession("Safari on iOS");
        givenExistingSessions(List.of(justCreated));

        assertThat(detector.evaluate(principal, "Safari on iOS", justCreated.getId())).isFalse();
    }

    @Test
    void anUnknownClientIsNotFlagged() {
        // No usable device description means no evidence either way.
        givenExistingSessions(List.of(existingSession("Chrome on Windows")));

        assertThat(detector.evaluate(principal, "   ", currentSessionId)).isFalse();
        assertThat(detector.evaluate(principal, null, currentSessionId)).isFalse();

        verify(securityEventService, never()).record(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void aFailureToRecordDoesNotBecomeAnError() {
        // The sign-in has already succeeded. An advisory signal that cannot be
        // written must not turn that into a 500.
        givenExistingSessions(List.of(existingSession("Chrome on Windows")));
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("store unavailable"))
                .when(securityEventService)
                .record(any(), any(), any(), anyString(), anyString());

        assertThat(detector.evaluate(principal, "Safari on iOS", currentSessionId)).isFalse();
    }

    @Test
    void withoutAPrincipalThereIsNoSignal() {
        // Nothing is evaluated on a failed sign-in, so there is no principal to
        // evaluate: the detector is unreachable rather than silently firing.
        assertThat(detector.evaluate(null, "Safari on iOS", currentSessionId)).isFalse();

        verify(securityEventService, never()).record(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void recentlyActiveRecognisedDeviceDoesNotRequireEmailVerification() {
        when(sessionRepository.findRecentDeviceSessionsByUserId(eq(principal.userId()), any(Pageable.class)))
                .thenReturn(List.of(existingSession("Chrome on Windows")));
        when(sessionRepository.findLatestActivityByUserId(principal.userId())).thenReturn(NOW.minusSeconds(3599));

        assertThat(detector.requiresEmailVerification(principal.userId(), "Chrome on Windows")).isFalse();
    }

    @Test
    void recentRecognisedDeviceRemainsTrustedAfterItsSessionWasLoggedOut() {
        AuthSession endedSession = existingSession("Chrome on Windows");
        endedSession.revoke(NOW.minusSeconds(30));
        when(sessionRepository.findRecentDeviceSessionsByUserId(eq(principal.userId()), any(Pageable.class)))
                .thenReturn(List.of(endedSession));
        when(sessionRepository.findLatestActivityByUserId(principal.userId())).thenReturn(NOW.minusSeconds(30));

        assertThat(detector.requiresEmailVerification(principal.userId(), "Chrome on Windows")).isFalse();
    }

    @Test
    void aRecognisedDeviceRequiresEmailVerificationAfterAnHourOfInactivity() {
        when(sessionRepository.findRecentDeviceSessionsByUserId(eq(principal.userId()), any(Pageable.class)))
                .thenReturn(List.of(existingSession("Chrome on Windows")));
        when(sessionRepository.findLatestActivityByUserId(principal.userId())).thenReturn(NOW.minusSeconds(3601));

        assertThat(detector.requiresEmailVerification(principal.userId(), "Chrome on Windows")).isTrue();
    }

    @Test
    void anUnfamiliarDeviceRequiresEmailVerificationEvenWithRecentActivity() {
        when(sessionRepository.findRecentDeviceSessionsByUserId(eq(principal.userId()), any(Pageable.class)))
                .thenReturn(List.of(existingSession("Chrome on Windows")));
        when(sessionRepository.findLatestActivityByUserId(principal.userId())).thenReturn(NOW);

        assertThat(detector.requiresEmailVerification(principal.userId(), "Safari on iOS")).isTrue();
    }
}