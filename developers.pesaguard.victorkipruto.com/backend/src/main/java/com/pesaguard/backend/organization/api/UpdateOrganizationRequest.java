package com.pesaguard.backend.organization.api;

import java.util.Map;

import com.pesaguard.backend.organization.domain.OrganizationType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateOrganizationRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotNull OrganizationType type,
        @NotNull @Size(max = 50) Map<@Size(min = 1, max = 80) String, Object> metadata) {
}