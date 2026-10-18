package com.pesaguard.backend.rbac.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RequestProductionAccessRequest(
        @NotNull UUID environmentId,
        @NotBlank @Size(min = 10, max = 1000) String reason,
        @NotBlank @Size(min = 2, max = 160) String applicationName,
        @NotBlank @Size(min = 10, max = 2000) String organizationDetails,
        @NotBlank @Size(min = 10, max = 2000) String intendedApiUsage,
        @NotBlank @Size(min = 2, max = 1000) String requestedScopes,
        @NotBlank @Size(min = 2, max = 1000) String requestedLimits,
        @NotBlank @Size(min = 10, max = 2000) String integrationInformation,
        @NotBlank @Size(min = 10, max = 2000) String securityInformation) {
}
