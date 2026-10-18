package com.pesaguard.backend.security.sessions;

import static org.assertj.core.api.Assertions.assertThat;
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

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.tokens.RevokedTokenRegistry;

class SessionQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-03T04:05:06Z");

    private final AuthSessionRepository sessionRepository = mock(AuthSessionRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final RevokedTokenRegistry revokedTokens = mock(RevokedTokenRegistry.class);
    private final SessionQueryService service = new SessionQueryService(
            sessionRepository,
            auditService,
            refreshTokenService,
            revokedTokens,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void revokeOthersKeepsCurrentSessionAndRevokesOtherAccessAndRefreshTokens() {
        UUID userId = UUID.randomUUID();
        UUID currentSessionId = UUID.randomUUID();
        AuthenticatedUser principal = principal(userId, currentSessionId);
        AuthSession otherSession = mock(AuthSession.class);
        UUID otherSessionId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant expiresAt = NOW.plusSeconds(300);
        when(otherSession.getId()).thenReturn(otherSessionId);
        when(otherSession.getRefreshFamilyId()).thenReturn(familyId);
        when(otherSession.getExpiresAt()).thenReturn(expiresAt);
        when(otherSession.isActive(NOW)).thenReturn(true);
        when(sessionRepository.findLiveSessionsByUserIdExcept(userId, currentSessionId))
                .thenReturn(List.of(otherSession));

        assertThat(service.revokeOthers(principal)).isEqualTo(1);

        verify(sessionRepository).findLiveSessionsByUserIdExcept(userId, currentSessionId);
        verify(otherSession).revoke(NOW);
        verify(sessionRepository).save(otherSession);
        verify(refreshTokenService).revokeFamilyById(familyId, "SESSION_REVOKED");
        verify(revokedTokens).revoke(otherSessionId, expiresAt);
        verify(auditService).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void expiredOtherSessionsAreNotCountedOrRewritten() {
        UUID userId = UUID.randomUUID();
        UUID currentSessionId = UUID.randomUUID();
        AuthSession expiredSession = mock(AuthSession.class);
        when(expiredSession.isActive(NOW)).thenReturn(false);
        when(sessionRepository.findLiveSessionsByUserIdExcept(userId, currentSessionId))
                .thenReturn(List.of(expiredSession));

        assertThat(service.revokeOthers(principal(userId, currentSessionId))).isZero();

        verify(sessionRepository, never()).save(expiredSession);
        verify(refreshTokenService, never()).revokeFamilyById(any(), any());
        verify(revokedTokens, never()).revoke(any(), any());
    }

    private AuthenticatedUser principal(UUID userId, UUID sessionId) {
        return new AuthenticatedUser(userId, UUID.randomUUID(), sessionId,
                "developer@example.com", "Developer", Set.of());
    }
}
