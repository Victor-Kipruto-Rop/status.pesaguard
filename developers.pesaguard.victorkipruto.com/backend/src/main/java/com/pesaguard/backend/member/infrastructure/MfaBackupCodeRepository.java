package com.pesaguard.backend.member.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.member.domain.MfaBackupCode;

public interface MfaBackupCodeRepository extends JpaRepository<MfaBackupCode, UUID> {

    Optional<MfaBackupCode> findByCodeHash(String codeHash);

    @Query("select code from MfaBackupCode code where code.secretId = :secretId")
    List<MfaBackupCode> findBySecretId(@Param("secretId") UUID secretId);

    void deleteBySecretId(UUID secretId);

    /** Spends a code only if still unused, so two concurrent uses cannot both win. */
    @Modifying
    @Query("update MfaBackupCode code set code.usedAt = :now "
            + "where code.codeHash = :codeHash and code.usedAt is null")
    int consumeIfUnused(@Param("codeHash") String codeHash, @Param("now") java.time.Instant now);
}