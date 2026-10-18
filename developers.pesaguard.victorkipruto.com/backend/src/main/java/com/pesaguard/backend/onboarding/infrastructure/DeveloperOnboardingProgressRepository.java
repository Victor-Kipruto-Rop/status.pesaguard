package com.pesaguard.backend.onboarding.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.onboarding.domain.DeveloperOnboardingProgress;

public interface DeveloperOnboardingProgressRepository extends JpaRepository<DeveloperOnboardingProgress, UUID> {
}
