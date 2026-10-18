package com.pesaguard.backend.organization.api;

import com.pesaguard.backend.organization.domain.OrganizationRole;

import jakarta.validation.constraints.NotNull;

public record ChangeMemberRoleRequest(@NotNull OrganizationRole role) {
}