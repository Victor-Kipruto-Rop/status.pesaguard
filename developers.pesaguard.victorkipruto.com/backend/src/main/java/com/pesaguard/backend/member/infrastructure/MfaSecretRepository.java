package com.pesaguard.backend.member.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.member.domain.MfaSecret;

public interface MfaSecretRepository extends JpaRepository<MfaSecret, UUID> {

    /**
     * Advances the consumed-step marker only if the presented step is newer.
     *
     * <p>The single-use guarantee for a TOTP code cannot be enforced by the
     * application: {@code consumeStep} reads the counter, compares, and writes,
     * and two requests carrying the same code can both read the pre-use value
     * before either writes. Expressing it as one conditional UPDATE lets the
     * database reject the second arrival, which is the only way the guarantee
     * actually holds under concurrency.
     *
     * @return 1 when this call advanced the counter, 0 when the step was already
     *         spent — which the caller must treat as a rejected code, not a retry
     *
     * <p>{@code clearAutomatically} is required, not cosmetic. A bulk update bypasses
     * the persistence context, so any already-loaded {@code MfaSecret} keeps the
     * pre-update counter in memory; saving that stale instance afterwards writes
     * every column, including the old counter, and silently undoes the claim.
     * Clearing forces the next read to come from the database.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MfaSecret secret set secret.lastUsedCounter = :counter "
            + "where secret.id = :id and secret.lastUsedCounter < :counter")
    int consumeStepIfNewer(@Param("id") UUID id, @Param("counter") long counter);

    /** The user's live factor, whether or not it has been confirmed yet. */
    Optional<MfaSecret> findByUserIdAndRevokedAtIsNull(UUID userId);

    /** Only a confirmed factor may satisfy a login challenge. */
    Optional<MfaSecret> findByUserIdAndRevokedAtIsNullAndConfirmedAtIsNotNull(UUID userId);

    List<MfaSecret> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}