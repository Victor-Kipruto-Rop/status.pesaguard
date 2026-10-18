package com.pesaguard.backend.project.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.project.domain.ProjectSettings;

public interface ProjectSettingsRepository extends JpaRepository<ProjectSettings, UUID> {

    Optional<ProjectSettings> findByProjectIdAndOrganizationId(UUID projectId, UUID organizationId);
}