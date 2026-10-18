package com.pesaguard.backend.onboarding.api;

import java.util.List;

public record DeveloperOnboardingWorkflowView(
        String status,
        String currentStep,
        DeveloperOnboardingProgressView progress,
        List<DeveloperOnboardingStepView> steps,
        long version) {

    public DeveloperOnboardingWorkflowView {
        steps = List.copyOf(steps);
    }
}
