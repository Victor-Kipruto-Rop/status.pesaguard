package com.pesaguard.backend.onboarding.api;

import java.util.List;
import java.util.UUID;

public record DeveloperOnboardingStatus(
        boolean emailVerified,
        boolean profileReady,
        boolean organizationReady,
        boolean projectReady,
        boolean environmentReady,
        String nextStep,
        boolean complete,
        boolean wizardStarted,
        boolean onboardingComplete,
        boolean skipped,
        String currentStep,
        List<String> completedSteps,
        List<String> recommendations,
        String organizationName,
        String organizationDescription,
        UUID projectId,
        String projectName,
        UUID environmentId,
        String environmentName) {

    public DeveloperOnboardingStatus {
        completedSteps = List.copyOf(completedSteps);
        recommendations = List.copyOf(recommendations);
    }
}
