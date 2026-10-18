package com.pesaguard.backend.security.passkeys;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, UUID> {
    List<PasskeyCredential> findAllByUserIdOrderByCreatedAtAsc(UUID userId);
    Optional<PasskeyCredential> findByIdAndUserId(UUID id, UUID userId);
    Optional<PasskeyCredential> findByCredentialId(byte[] credentialId);
    List<PasskeyCredential> findAllByCredentialId(byte[] credentialId);
    boolean existsByUserId(UUID userId);
}
