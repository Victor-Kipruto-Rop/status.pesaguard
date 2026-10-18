package com.pesaguard.backend.oauth.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.pesaguard.backend.oauth.domain.AuthorizationCode;

public interface AuthorizationCodeRepository extends JpaRepository<AuthorizationCode, UUID> {

    Optional<AuthorizationCode> findByCodeHash(String codeHash);

    /**
     * Row-locking read used when redeeming a code. Without it, two concurrent
     * exchanges could both observe an unconsumed code and both mint tokens.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuthorizationCode c where c.codeHash = :codeHash")
    Optional<AuthorizationCode> findByCodeHashForUpdate(@Param("codeHash") String codeHash);
}