package com.pesaguard.backend.member.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.member.domain.UserRefreshToken;

/**
 * Refresh tokens backing developer sign-in sessions.
 *
 * <p>Named for the {@code user_refresh_tokens} table rather than simply
 * {@code RefreshTokenRepository}: an unrelated OAuth grant repository already
 * holds that simple name, and Spring derives the bean name from the interface
 * name — two beans called {@code refreshTokenRepository} make the application
 * context fail to start.
 */
public interface UserRefreshTokenRepository extends JpaRepository<UserRefreshToken, UUID> {

    Optional<UserRefreshToken> findByTokenHash(String tokenHash);

    /**
     * Spends a token atomically, returning 1 only to the caller that won.
     *
     * <p>This is the concurrency guard for rotation. Two tabs refreshing at the
     * same instant both read the token as unused, and a read-then-write would let
     * both proceed to mint a successor — leaving two live tokens where the
     * invariant says exactly one. Making the spend a single conditional UPDATE
     * means the database, not the application, decides the winner; the loser sees
     * zero rows updated and is handled as the replay it is.
     *
     * <p>Deliberately guarded on {@code usedAt} alone and not re-checking expiry
     * here: the caller has already validated the window, and duplicating that
     * predicate across two places would let the two disagree.
     *
     * <p>{@code clearAutomatically} matters for the same reason as elsewhere: the
     * caller holds the token it loaded before this update ran, and writing that
     * stale instance back would restore {@code usedAt} to null and hand out a
     * second live token.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserRefreshToken token set token.usedAt = :now "
            + "where token.id = :id and token.usedAt is null")
    int claimForRotation(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Outstanding tokens in a family, oldest first.
     *
     * <p>Used when a family is revoked: every descendant has to die, not just the
     * presented one, or a leaked sibling stays usable after reuse was detected.
     */
    @Query("""
            select token from UserRefreshToken token
            where token.familyId = :familyId and token.revokedAt is null
            """)
    List<UserRefreshToken> findActiveByFamilyId(@Param("familyId") UUID familyId);

    /** Outstanding refresh tokens for a user, across every organization. */
    @Query("""
            select token from UserRefreshToken token
            where token.familyId in (select family.id from RefreshTokenFamily family
                where family.userId = :userId and family.revokedAt is null)
              and token.revokedAt is null
              and token.expiresAt > :now
            """)
    List<UserRefreshToken> findActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}