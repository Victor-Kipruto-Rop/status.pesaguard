package com.pesaguard.backend.member.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.member.domain.RefreshTokenFamily;

public interface RefreshTokenFamilyRepository extends JpaRepository<RefreshTokenFamily, UUID> {

    Optional<RefreshTokenFamily> findByIdAndUserId(UUID id, UUID userId);

    List<RefreshTokenFamily> findByUserIdAndRevokedAtIsNull(UUID userId);
}