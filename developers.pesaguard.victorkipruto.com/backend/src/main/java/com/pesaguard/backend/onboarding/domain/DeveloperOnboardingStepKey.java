package com.pesaguard.backend.onboarding.domain;

import java.util.Arrays;
import java.util.Optional;

public enum DeveloperOnboardingStepKey {
    ACCOUNT,
    EMAIL_VERIFICATION,
    SECURITY,
    PROFILE,
    ORGANIZATION,
    PROJECT,
    SANDBOX,
    API_KEY,
    FIRST_API_REQUEST,
    API_EXPLORER,
    INTEGRATION,
    WEBHOOK,
    DOCUMENTATION,
    TEAM,
    PRODUCTION_READINESS,
    PRODUCTION_REQUEST,
    PRODUCTION_APPROVAL,
    PRODUCTION_CREDENTIALS,
    COMPLETION;

    public static Optional<DeveloperOnboardingStepKey> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(step -> step.name().equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
