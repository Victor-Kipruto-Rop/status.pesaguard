package com.pesaguard.backend.member.infrastructure;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.pesaguard.backend.member.domain.UserAccount;
import com.pesaguard.backend.member.domain.UserStatus;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByEmail(String email);

    Optional<UserAccount> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCaseAndIdNot(String username, UUID id);

    boolean existsByPhoneNumber(String phoneNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from UserAccount account where account.email = :email")
    Optional<UserAccount> findByEmailForUpdate(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from UserAccount account where account.loginMfaChallengeId = :challengeId")
    Optional<UserAccount> findByLoginMfaChallengeIdForUpdate(@Param("challengeId") UUID challengeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from UserAccount account where account.passwordChangeChallengeId = :challengeId")
    Optional<UserAccount> findByPasswordChangeChallengeIdForUpdate(@Param("challengeId") UUID challengeId);

    /**
     * Lookup by outstanding verification hash.
     *
     * <p>Exists so confirmation is an index hit rather than a scan of every
     * account, which would make an attacker-supplied token a denial-of-service
     * vector against the login table.
     */
    Optional<UserAccount> findByEmailVerificationHash(String emailVerificationHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from UserAccount account where account.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select account from UserAccount account
            where account.status = :status and account.deletionRequestedAt <= :cutoff
            order by account.deletionRequestedAt asc
            """)
    List<UserAccount> findPendingDeletionBefore(
            @Param("status") UserStatus status, @Param("cutoff") Instant cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select account from UserAccount account
            where account.status = :status and account.deactivatedUntil <= :cutoff
            order by account.deactivatedUntil asc
            """)
    List<UserAccount> findPausedAccountsDue(
            @Param("status") UserStatus status, @Param("cutoff") Instant cutoff);
}
