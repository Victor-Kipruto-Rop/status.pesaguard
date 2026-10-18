package com.pesaguard.backend.onboarding.api;

import com.pesaguard.backend.project.api.ProjectView;

public record DeveloperWorkspaceBootstrapView(
        ProjectView project,
        String template,
        String firstEndpoint) {
}
