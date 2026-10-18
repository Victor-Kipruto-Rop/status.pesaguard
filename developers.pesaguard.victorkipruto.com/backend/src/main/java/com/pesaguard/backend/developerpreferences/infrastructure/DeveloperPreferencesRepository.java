package com.pesaguard.backend.developerpreferences.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.developerpreferences.domain.DeveloperPreferences;

public interface DeveloperPreferencesRepository extends JpaRepository<DeveloperPreferences, UUID> {

    Optional<DeveloperPreferences> findByUserId(UUID userId);
}
