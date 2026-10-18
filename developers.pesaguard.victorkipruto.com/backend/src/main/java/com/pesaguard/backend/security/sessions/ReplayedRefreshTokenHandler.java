package com.pesaguard.backend.security.sessions;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.member.domain.UserRefreshToken;
import com.pesaguard.backend.member.domain.RefreshTokenFamily;
import com.pesaguard.backend.member.infrastructure.RefreshTokenFamilyRepository;
import com.pesaguard.backend.member.infrastructure.UserRefreshTokenRepository;
import com.pesaguard.backend.securitycenter.application.SecurityEventService;
import com.pesaguard.backend.securitycenter.domain.SecurityEventType;

/**
 * The response to a replayed refresh token, in its own transaction.
 *
 * <p>This exists as a separate bean because of a specific failure mode. The
 * obvious implementation revokes the family and then throws a 401 from inside
 * the same {@code @Transactional} method — but a thrown exception marks the
 * transaction for rollback, so the revocation is discarded along with it. The
 * attacker gets a 401 telling them the token is burned, while every token in the
 * family stays live and redeemable. The detection would look like it worked in
 * the logs while changing nothing in the database.
 *
 * <p>{@link Propagation#REQUIRES_NEW} suspends the caller's transaction so this
 * work commits independently. The caller still rolls back, still returns 401, and
 * the kill persists.
 *
 * <p>A bean rather than a self-call on {@code RefreshTokenService}, because
 * Spring's proxying cannot intercept an internal method call and
 * {@code REQUIRES_NEW} would silently degrade to the ambient transaction.
 */
@Component
public class ReplayedRefreshTokenHandler {

    private final UserRefreshTokenRepository tokenRepository;
    private final RefreshTokenFamilyRepository familyRepository;
    private final SecurityEventService securityEventService;

    public ReplayedRefreshTokenHandler(
            UserRefreshTokenRepository tokenRepository,
            RefreshTokenFamilyRepository familyRepository,
            SecurityEventService securityEventService) {
        this.tokenRepository = tokenRepository;
        this.familyRepository = familyRepository;
        this.securityEventService = securityEventService;
    }

    /**
     * Kills the family a replayed token belongs to and records the attempt.
     *
     * <p>The family is re-read rather than passed in, because the caller's copy
     * belongs to a transaction that is about to be rolled back and its managed
     * state cannot be relied on here.
     *
     * <p>Silent on an unknown family: a replay against a family that has already
     * been cleaned up has already achieved the required end state, and failing
     * here would turn a correct 401 into a 500.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamilyAndRecord(UUID familyId, Instant now) {
        familyRepository.findById(familyId).ifPresent(family -> {
            family.revoke("REUSE_DETECTED", now);
            familyRepository.save(family);
            for (UserRefreshToken live : tokenRepository.findActiveByFamilyId(familyId)) {
                live.revoke(now);
                tokenRepository.save(live);
            }
            securityEventService.record(family.getOrganizationId(), SecurityEventType.TOKEN_REPLAY,
                    family.getUserId(), "user",
                    "A refresh token was presented more than once. Every session for this login was revoked.");
        });
    }
}