package com.pesaguard.backend.onboarding.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.onboarding.domain.DeveloperOnboardingStepRecord;

public interface DeveloperOnboardingStepRepository extends JpaRepository<DeveloperOnboardingStepRecord, UUID> {

    List<DeveloperOnboardingStepRecord> findByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<DeveloperOnboardingStepRecord> findByUserIdAndStepKey(UUID userId, String stepKey);
}
