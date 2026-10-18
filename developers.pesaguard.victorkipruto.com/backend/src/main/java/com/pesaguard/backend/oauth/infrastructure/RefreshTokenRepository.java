package com.pesaguard.backend.oauth.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.pesaguard.backend.oauth.domain.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Row-locking read used during rotation. Without it, two concurrent refreshes
     * could both pass the replay check and mint two valid successors.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<RefreshToken> findByFamilyId(UUID familyId);

    List<RefreshToken> findByApplicationIdAndUserId(UUID applicationId, UUID userId);

    List<RefreshToken> findByApplicationId(UUID applicationId);
}