package com.pesaguard.backend.environment.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.environment.domain.EnvironmentCredential;
import com.pesaguard.backend.environment.domain.EnvironmentCredentialStatus;

public interface EnvironmentCredentialRepository extends JpaRepository<EnvironmentCredential, UUID> {

    List<EnvironmentCredential> findByEnvironmentIdOrderByCreatedAtDesc(UUID environmentId);

    /**
     * Whether this secret is already stored under a different environment.
     *
     * <p>Exists to stop a production secret being reused in sandbox. The fingerprint
     * is a keyed digest of the secret, so the same secret produces the same value in
     * every tier and a collision here means literal reuse, not coincidence.
     */
    boolean existsByFingerprintAndProjectIdAndEnvironmentIdNot(
            String fingerprint, UUID projectId, UUID environmentId);

    long countByEnvironmentIdAndStatus(UUID environmentId, EnvironmentCredentialStatus status);

    /**
     * Tenant-scoped lookup, so a credential cannot be revoked through a
     * borrowed organization, project, or environment identifier.
     */
    Optional<EnvironmentCredential> findByIdAndEnvironmentIdAndProjectIdAndOrganizationId(
            UUID id, UUID environmentId, UUID projectId, UUID organizationId);

    long countByEnvironmentIdAndNameAndStatus(
            UUID environmentId, String name, EnvironmentCredentialStatus status);

    List<EnvironmentCredential> findByEnvironmentIdAndNameOrderByVersionDesc(UUID environmentId, String name);
}