package com.pesaguard.backend.organization.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.organization.domain.OrganizationSecuritySettings;

public interface OrganizationSecuritySettingsRepository extends JpaRepository<OrganizationSecuritySettings, UUID> {
}