package com.pesaguard.backend.security.sessions;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.tokens.RevokedTokenRegistry;

/**
 * Reads and ends sessions on a user's behalf.
 *
 * <p>Every method is scoped by the authenticated user id taken from the
 * principal, never from a request body. A user asking to end "session 42" must
 * only ever be able to end their own session 42; scoping by the submitted id
 * alone would let one user terminate another's session, which is a denial of
 * service even though it discloses nothing.
 */
@Service
public class SessionQueryService {

    private final AuthSessionRepository sessionRepository;
    private final AuditService auditService;
    private final RefreshTokenService refreshTokenService;
    private final RevokedTokenRegistry revokedTokens;
    private final Clock clock;

    public SessionQueryService(AuthSessionRepository sessionRepository, AuditService auditService,
            RefreshTokenService refreshTokenService, RevokedTokenRegistry revokedTokens, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.auditService = auditService;
        this.refreshTokenService = refreshTokenService;
        this.revokedTokens = revokedTokens;
        this.clock = clock;
    }

    /** A user's live sessions, most recently seen first. */
    @Transactional(readOnly = true)
    public List<AuthSession> activeFor(AuthenticatedUser principal) {
        return sessionRepository.findActiveByUserIdOrderByLastSeenAtDesc(
                principal.userId(), clock.instant());
    }

    /**
     * Ends one of the caller's own sessions.
     *
     * <p>Also revokes the refresh family backing it. Ending only the access token
     * would leave the caller holding a live refresh token that mints a new session
     * on the next refresh — so "sign out this device" would not actually sign the
     * device out.
     *
     * @return true if a live session was ended; false if it did not exist,
     *         was already revoked, or belongs to someone else
     */
    @Transactional
    public boolean revoke(AuthenticatedUser principal, UUID sessionId) {
        Instant now = clock.instant();
        Optional<AuthSession> found = sessionRepository
                .findByIdAndMembershipUserId(sessionId, principal.userId());
        if (found.isEmpty() || !found.get().isActive(now)) {
            // A session belonging to another user is reported exactly like a
            // missing one, so this endpoint cannot be used to probe which session
            // ids exist.
            return false;
        }
        revokeSession(principal, found.get(), now);
        return true;
    }

    /**
     * Ends every live session owned by the caller except the session used here.
     *
     * <p>The session rows, refresh families and access-token denylist are all
     * updated, so another device cannot refresh or keep using an access token.
     */
    @Transactional
    public int revokeOthers(AuthenticatedUser principal) {
        Instant now = clock.instant();
        int revoked = 0;
        for (AuthSession session : sessionRepository.findLiveSessionsByUserIdExcept(
                principal.userId(), principal.sessionId())) {
            if (session.isActive(now)) {
                revokeSession(principal, session, now);
                revoked++;
            }
        }
        return revoked;
    }

    private void revokeSession(AuthenticatedUser principal, AuthSession session, Instant now) {
        UUID familyId = session.getRefreshFamilyId();
        Instant expiry = session.getExpiresAt();
        session.revoke(now);
        sessionRepository.save(session);
        if (familyId != null) {
            refreshTokenService.revokeFamilyById(familyId, "SESSION_REVOKED");
        }
        // Denylist the access token so "sign out this device" takes effect now
        // rather than when the token expires. Same reasoning as logout: an explicit
        // revocation is not a hot-path request, and a revoked session that keeps
        // working for another four minutes is the exact outcome this endpoint
        // exists to prevent.
        revokedTokens.revoke(session.getId(), expiry);
        auditService.append(principal.organizationId(), principal.userId(), "auth.session.revoked",
                "session", session.getId().toString(), RequestContext.currentRequestId(),
                java.util.Map.of("remoteSession", !session.getId().equals(principal.sessionId())));
    }
}