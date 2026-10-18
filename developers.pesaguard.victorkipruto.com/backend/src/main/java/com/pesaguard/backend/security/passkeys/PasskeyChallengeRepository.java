package com.pesaguard.backend.security.passkeys;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import org.springframework.data.jpa.repository.Modifying;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasskeyChallengeRepository extends JpaRepository<PasskeyChallenge, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select challenge from PasskeyChallenge challenge where challenge.id = :id")
    Optional<PasskeyChallenge> findByIdForUpdate(@Param("id") UUID id);

    @Modifying
    @Query("delete from PasskeyChallenge challenge where challenge.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
