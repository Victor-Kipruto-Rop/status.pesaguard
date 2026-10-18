package com.pesaguard.backend.member.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.member.domain.PasswordResetToken;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** The one outstanding reset for a user, if any. */
    @Query("""
            select token from PasswordResetToken token
            where token.userId = :userId and token.usedAt is null and token.revokedAt is null
            """)
    List<PasswordResetToken> findOutstandingByUserId(@Param("userId") UUID userId);
}