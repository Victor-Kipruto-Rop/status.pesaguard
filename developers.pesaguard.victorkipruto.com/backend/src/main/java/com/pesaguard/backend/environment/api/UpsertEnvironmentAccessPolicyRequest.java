package com.pesaguard.backend.environment.api;

import java.util.Set;

import com.pesaguard.backend.environment.domain.EnvironmentAccessSubjectType;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;

public record UpsertEnvironmentAccessPolicyRequest(
        @NotNull EnvironmentAccessSubjectType subjectType,
        @NotNull @NotBlank String subjectRole,
        @NotEmpty Set<EnvironmentPermission> permissions,
        Set<String> ipAllowlist) {
}
