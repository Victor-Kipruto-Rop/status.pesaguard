package com.pesaguard.backend.integration.application;

import java.util.Set;
import java.util.UUID;

import com.pesaguard.backend.environment.domain.EnvironmentType;

record IntegrationConnectionContext(
        UUID integrationId,
        UUID organizationId,
        UUID projectId,
        UUID environmentId,
        EnvironmentType environmentType,
        String encryptedSecret,
        Set<String> scopes,
        UUID testRunId,
        UUID requestId) {
}
