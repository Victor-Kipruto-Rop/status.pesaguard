package com.pesaguard.backend.security.sessions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.UnauthorizedException;
import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.member.domain.UserRefreshToken;
import com.pesaguard.backend.member.domain.RefreshTokenFamily;
import com.pesaguard.backend.member.infrastructure.RefreshTokenFamilyRepository;
import com.pesaguard.backend.member.infrastructure.UserRefreshTokenRepository;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.securitycenter.application.SecurityEventService;
import com.pesaguard.backend.securitycenter.domain.SecurityEventType;

/**
 * Rotating refresh tokens, with reuse detection.
 *
 * <p>Each login starts a family and gets one token. Presenting a token spends it
 * and mints its successor in the same family. The design rule that matters:
 *
 * <p><b>A token may be presented exactly once.</b> Presenting an already-spent
 * token therefore means the value leaked, and the only safe response is to kill
 * the entire family and require re-authentication. That response is deliberately
 * indiscriminate: distinguishing "attacker replayed a stolen token" from
 * "legitimate client raced itself" is not possible from the evidence available,
 * and guessing wrong either leaves a stolen session alive or locks out an honest
 * user mid-incident.
 *
 * <p>All values are hashed on the way in. Nothing readable by a database reader
 * can be replayed here.
 */
@Service
public class RefreshTokenService {

    /** Fallback when no TTL is configured. */
    public static final Duration DEFAULT_REFRESH_TTL = Duration.ofDays(30);

    private final UserRefreshTokenRepository tokenRepository;
    private final RefreshTokenFamilyRepository familyRepository;
    private final CredentialCryptoService credentialCryptoService;
    private final SecurityEventService securityEventService;
    private final ReplayedRefreshTokenHandler replayHandler;
    private final ApplicationProperties properties;
    private final SecretKey credentialKey;
    private final Clock clock;

    public RefreshTokenService(
            UserRefreshTokenRepository tokenRepository,
            RefreshTokenFamilyRepository familyRepository,
            CredentialCryptoService credentialCryptoService,
            SecurityEventService securityEventService,
            ReplayedRefreshTokenHandler replayHandler,
            ApplicationProperties properties,
            @Qualifier("credentialHmacKey") SecretKey credentialKey,
            Clock clock) {
        this.tokenRepository = tokenRepository;
        this.familyRepository = familyRepository;
        this.credentialCryptoService = credentialCryptoService;
        this.securityEventService = securityEventService;
        this.replayHandler = replayHandler;
        this.properties = properties;
        this.credentialKey = credentialKey;
        this.clock = clock;
    }

    /** Starts a family and issues its first token. */
    @Transactional
    public IssuedRefreshToken issue(OrganizationMembership membership, String deviceLabel, String lastIp) {
        Instant now = clock.instant();
        RefreshTokenFamily family = familyRepository.save(RefreshTokenFamily.start(
                membership.getUser().getId(), membership.getOrganization().getId()));
        return mint(family, now, deviceLabel, lastIp);
    }

    private IssuedRefreshToken mint(RefreshTokenFamily family, Instant now, String deviceLabel, String lastIp) {
        String token = credentialCryptoService.randomToken(32);
        UserRefreshToken refreshToken = tokenRepository.save(UserRefreshToken.issue(
                family.getId(), credentialCryptoService.sha256(token), now,
                now.plus(refreshTtl()), deviceLabel, lastIp));
        family.recordRotation(now);
        familyRepository.save(family);
        return new IssuedRefreshToken(token, refreshToken.getExpiresAt(), family.getId());
    }

    /**
     * Exchanges a refresh token for a fresh one.
     *
     * <p>The old token is spent in the same transaction that mints its
     * replacement, so a failure between the two cannot leave both usable.
     *
     * <p>The spend is a conditional UPDATE rather than a read-then-write. Two
     * browser tabs refreshing simultaneously both observe an unused token, and only
     * the database can say which of them proceeds; the loser here is
     * indistinguishable from a replay, and is treated as one.
     */
    @Transactional
    public IssuedRefreshToken rotate(String presentedToken, String deviceLabel, String lastIp) {
        Instant now = clock.instant();
        UserRefreshToken token = tokenRepository.findByTokenHash(
                        credentialCryptoService.sha256(presentedToken))
                .orElseThrow(this::invalidRefreshToken);

        RefreshTokenFamily family = familyRepository.findById(token.getFamilyId())
                .orElseThrow(this::invalidRefreshToken);

        // A revoked family means the login was already ended (sign-out-everywhere,
        // a password reset, or an earlier replay). The token row may still look
        // individually intact, but the family it belongs to is dead.
        if (!family.isActive()) {
            throw invalidRefreshToken();
        }

        // Expiry and prior revocation only. Notably NOT "isUsed": a token that was
        // already spent must fall through to the claim below, which returns zero
        // and routes it to the replay path. Returning early here would refuse the
        // replay without revoking the family, leaving the stolen chain live — the
        // detection would fire while changing nothing.
        if (token.isExpired(now) || token.getRevokedAt() != null) {
            throw invalidRefreshToken();
        }

        if (tokenRepository.claimForRotation(token.getId(), now) != 1) {
            // Lost the race, or this token was already spent: another request
            // consumed it first. Either way the family is treated as compromised.
            // The revocation must outlive the rollback the throw below causes,
            // hence the separate bean.
            replayHandler.revokeFamilyAndRecord(family.getId(), now);
            throw replayDetected();
        }

        return mint(family, now, deviceLabel, lastIp);
    }

    /**
     * Reads the family a freshly rotated token belongs to.
     *
     * <p>Returned from the rotation that just succeeded rather than looked up by
     * the caller independently, so the membership resolution cannot drift onto a
     * different family than the one the presented token actually belonged to.
     */
    @Transactional(readOnly = true)
    public RefreshTokenFamily requireFamily(UUID familyId) {
        return familyRepository.findById(familyId).orElseThrow(this::invalidRefreshToken);
    }

    /**
     * Kills every outstanding token in a family.
     *
     * <p>Both the family and each live token are revoked. Revoking only the
     * family would leave the token rows individually redeemable, which is exactly
     * the state reuse detection relies on being able to recognise.
     */
    @Transactional
    public void revokeFamily(RefreshTokenFamily family, String reason, Instant now) {
        family.revoke(reason, now);
        familyRepository.save(family);
        for (UserRefreshToken live : tokenRepository.findActiveByFamilyId(family.getId())) {
            live.revoke(now);
            tokenRepository.save(live);
        }
    }

    /**
     * Revokes every refresh family for a user.
     *
     * <p>Used on password reset and on explicit sign-out-everywhere. Both are
     * security events, not conveniences: a password change that left refresh
     * tokens alive would let an attacker hold access indefinitely.
     */
    @Transactional
    public int revokeAllForUser(UUID userId, String reason) {
        Instant now = clock.instant();
        int revoked = 0;
        for (RefreshTokenFamily family : familyRepository.findByUserIdAndRevokedAtIsNull(userId)) {
            revokeFamily(family, reason, now);
            revoked++;
        }
        return revoked;
    }

    /**
     * Revokes one family by id, if it exists.
     *
     * <p>Used when a single session is ended, so that ending a session also ends
     * the refresh token that could otherwise resurrect it.
     *
     * @return true if a live family was revoked
     */
    @Transactional
    public boolean revokeFamilyById(UUID familyId, String reason) {
        Instant now = clock.instant();
        Optional<RefreshTokenFamily> family = familyRepository.findById(familyId);
        if (family.isEmpty() || !family.get().isActive()) {
            return false;
        }
        revokeFamily(family.get(), reason, now);
        return true;
    }

    private UnauthorizedException invalidRefreshToken() {
        return new UnauthorizedException("INVALID_REFRESH_TOKEN",
                "The refresh token is invalid or has expired.");
    }

    private UnauthorizedException replayDetected() {
        return new UnauthorizedException("REFRESH_TOKEN_REUSE",
                "This refresh token has already been used. All sessions have been revoked; sign in again.");
    }

    private Duration refreshTtl() {
        Duration configured = properties.security().refreshTokenTtl();
        return configured == null ? DEFAULT_REFRESH_TTL : configured;
    }

    /** A minted refresh token. The plaintext is returned once and never stored. */
    public record IssuedRefreshToken(String token, Instant expiresAt, UUID familyId) {
    }
}