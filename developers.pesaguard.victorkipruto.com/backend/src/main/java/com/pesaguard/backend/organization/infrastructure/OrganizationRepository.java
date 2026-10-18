package com.pesaguard.backend.organization.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.organization.domain.Organization;

import jakarta.persistence.LockModeType;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findBySlug(String slug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select organization from Organization organization where organization.id = :id")
    Optional<Organization> findByIdForAudit(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select organization from Organization organization where organization.id = :id")
    Optional<Organization> findByIdForUpdate(@Param("id") UUID id);
}
