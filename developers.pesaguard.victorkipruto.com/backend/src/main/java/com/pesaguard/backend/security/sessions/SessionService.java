package com.pesaguard.backend.security.sessions;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.security.tokens.AccessTokenService;
import com.pesaguard.backend.security.tokens.RevokedTokenRegistry;

@Service
public class SessionService {

    private final AuthSessionRepository sessionRepository;
    private final CredentialCryptoService credentialCryptoService;
    private final AccessTokenService accessTokenService;
    private final RevokedTokenRegistry revokedTokens;
    private final ApplicationProperties properties;
    private final Clock clock;

    public SessionService(
            AuthSessionRepository sessionRepository,
            CredentialCryptoService credentialCryptoService,
            AccessTokenService accessTokenService,
            RevokedTokenRegistry revokedTokens,
            ApplicationProperties properties,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.credentialCryptoService = credentialCryptoService;
        this.accessTokenService = accessTokenService;
        this.revokedTokens = revokedTokens;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public IssuedSession issue(OrganizationMembership membership) {
        return issue(membership, properties.security().sessionTtl(), 0, null, null);
    }

    @Transactional
    public IssuedSession issue(OrganizationMembership membership, java.time.Duration sessionTtl) {
        return issue(membership, sessionTtl, 0, null, null);
    }

    @Transactional
    public IssuedSession issue(
            OrganizationMembership membership, java.time.Duration sessionTtl, int maxSessions) {
        return issue(membership, sessionTtl, maxSessions, null, null);
    }

    /**
     * Issues a session and its signed access token.
     *
     * <p>The session row is still written, because it is what the refresh path
     * validates against and what {@code /auth/sessions} lists. It is not consulted
     * when authenticating a request: the access token carries everything needed
     * to rebuild the principal, which is what keeps the request path free of a
     * database read.
     *
     * @param deviceLabel coarse client description for the sessions list
     * @param lastIp address the session was created from
     */
    @Transactional
    public IssuedSession issue(
            OrganizationMembership membership, java.time.Duration sessionTtl, int maxSessions,
            String deviceLabel, String lastIp) {
        Instant now = clock.instant();
        enforceSessionLimit(membership.getId(), maxSessions, now);
        AuthSession session = AuthSession.create(
                // The access token is a signed JWT, so the row needs its own random
                // "issued from" marker rather than a hash of the credential itself.
                // It is used to correlate a session row with the token it produced,
                // not to authenticate anything.
                credentialCryptoService.randomToken(32),
                membership,
                now,
                now.plus(sessionTtl),
                deviceLabel,
                lastIp);
        sessionRepository.save(session);

        AuthenticatedUser principal = new AuthenticatedUser(
                membership.getUser().getId(),
                membership.getOrganization().getId(),
                session.getId(),
                membership.getUser().getEmail(),
                membership.getUser().getDisplayName(),
                Set.of("ROLE_" + membership.getRole().name()));
        AccessTokenService.IssuedAccessToken access = accessTokenService.issue(principal, now);
        return new IssuedSession(access.token(), session.getId(), access.expiresAt());
    }

    /**
     * Issues a short-lived access token that can only complete MFA enrolment.
     * It deliberately creates neither a normal session row nor a refresh family.
     */
    public AccessTokenService.IssuedAccessToken issueMfaEnrollmentChallenge(
            OrganizationMembership membership) {
        Instant now = clock.instant();
        AuthenticatedUser principal = new AuthenticatedUser(
                membership.getUser().getId(),
                membership.getOrganization().getId(),
                UUID.randomUUID(),
                membership.getUser().getEmail(),
                membership.getUser().getDisplayName(),
                Set.of("ROLE_" + membership.getRole().name()),
                membership.getOrganization().getStatus(),
                false,
                Set.of(),
                true);
        return accessTokenService.issue(principal, now);
    }

    public void revokeMfaEnrollmentChallenge(UUID challengeId) {
        revokedTokens.revoke(challengeId, clock.instant().plus(accessTokenService.accessTokenTtl()));
    }

    private void enforceSessionLimit(UUID membershipId, int maxSessions, Instant now) {
        if (maxSessions <= 0) {
            return;
        }
        List<AuthSession> active = sessionRepository.findActiveByMembershipIdOrderByLastSeenAtAsc(
                membershipId, now);
        int excess = active.size() - (maxSessions - 1);
        for (int index = 0; index < excess; index++) {
            AuthSession session = active.get(index);
            session.revoke(now);
            sessionRepository.save(session);
        }
    }

    /**
     * Revokes every live session in an organization, and denylists their tokens.
     *
     * <p>Bulk revocation denylists each affected token id rather than relying on
     * expiry. Without it, suspending or deleting an organization would leave every
     * outstanding access token working for up to one token lifetime — the interval
     * in which a suspended tenant still has full API access, which is precisely
     * the window an operator is trying to close.
     *
     * @return how many sessions were ended
     */
    @Transactional
    public int revokeActiveByOrganizationId(UUID organizationId) {
        Instant now = clock.instant();
        denylist(sessionRepository.findLiveSessionsByOrganizationId(organizationId));
        return sessionRepository.revokeActiveByOrganizationId(organizationId, now);
    }

    /**
     * Revokes every live session for one membership, and denylists their tokens.
     *
     * @return how many sessions were ended
     */
    @Transactional
    public int revokeActiveByMembershipId(UUID membershipId) {
        Instant now = clock.instant();
        denylist(sessionRepository.findLiveSessionsByMembershipId(membershipId));
        return sessionRepository.revokeActiveByMembershipId(membershipId, now);
    }

    /**
     * Revokes every live session for a user, in every organization.
     *
     * <p>Scoped by user rather than by organization on purpose. A compromised
     * password must not leave the attacker holding a session in the victim's
     * second organization, which is the case an organization-scoped revocation
     * would silently miss.
     *
     * @return how many sessions were ended
     */
    @Transactional
    public int revokeActiveByUserId(UUID userId) {
        Instant now = clock.instant();
        denylist(sessionRepository.findLiveSessionsByUserId(userId));
        return sessionRepository.revokeActiveByUserId(userId, now);
    }

    /**
     * Revokes every live session for a user except the one they are using.
     *
     * <p>Used on password change: the caller should not be signed out of the
     * device they are using right now, but every other session must end. A null
     * {@code exceptSessionId} revokes all of them.
     *
     * @return how many sessions were ended
     */
    @Transactional
    public int revokeActiveByUserIdExcept(UUID userId, UUID exceptSessionId) {
        Instant now = clock.instant();
        denylist(sessionRepository.findLiveSessionsByUserIdExcept(userId, exceptSessionId));
        return sessionRepository.revokeActiveByUserIdExcept(userId, exceptSessionId, now);
    }

    /**
     * Marks each named session's access token unusable until it would expire anyway.
     *
     * <p>Deliberately fails open on an individual Redis error. A revocation that
     * could not be recorded must not abandon the database revocation that follows,
     * or a transient cache outage would silently leave sessions live. The row-level
     * revocation is what actually ends the session; this only closes the remaining
     * window in which the access token still verifies.
     */
    private void denylist(List<AuthSession> sessions) {
        for (AuthSession session : sessions) {
            try {
                revokedTokens.revoke(session.getId(), session.getExpiresAt());
            } catch (RuntimeException unavailable) {
                // Intentionally swallowed; see above.
            }
        }
    }

    /**
     * Records which refresh family backs an already-issued session.
     *
     * <p>Separate from {@code issue} because the family is minted by the refresh
     * service, which needs a membership this method has already persisted. Linking
     * afterwards is what lets a later logout revoke both halves of the credential
     * pair rather than only the access token.
     *
     * <p>Silent when the session id is unknown, because every caller has just
     * created it and a missing row would be a bug worth surfacing in tests rather
     * than a client-visible error.
     */
    @Transactional
    public void linkRefreshFamily(UUID sessionId, UUID familyId) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.linkRefreshFamily(familyId);
            sessionRepository.save(session);
        });
    }

    /** The refresh family backing a session, if one was linked. */
    @Transactional(readOnly = true)
    public Optional<UUID> refreshFamilyFor(UUID sessionId) {
        return sessionRepository.findById(sessionId).map(AuthSession::getRefreshFamilyId);
    }

    /**
     * When a session's access token stops being accepted anyway.
     *
     * <p>Used to bound a denylist entry: an entry that outlives its token is dead
     * weight in Redis, and one shorter than the token would let a revoked session
     * become valid again.
     */
    @Transactional(readOnly = true)
    public Optional<Instant> expiryFor(UUID sessionId) {
        return sessionRepository.findById(sessionId).map(AuthSession::getExpiresAt);
    }

    @Transactional
    public boolean revoke(AuthenticatedUser principal) {
        return sessionRepository.findByIdAndMembershipOrganizationId(
                        principal.sessionId(), principal.organizationId())
                .map(session -> {
                    boolean changed = session.getRevokedAt() == null;
                    session.revoke(clock.instant());
                    return changed;
                })
                .orElse(false);
    }

    private AuthenticatedUser toPrincipal(AuthSession session, OrganizationMembership membership) {
        Set<String> authorities = Set.of("ROLE_" + membership.getRole().name());
        return new AuthenticatedUser(
                membership.getUser().getId(),
                membership.getOrganization().getId(),
                session.getId(),
                membership.getUser().getEmail(),
                membership.getUser().getDisplayName(),
                authorities,
                membership.getOrganization().getStatus());
    }

    public record IssuedSession(String token, UUID sessionId, Instant expiresAt) {
    }
}
