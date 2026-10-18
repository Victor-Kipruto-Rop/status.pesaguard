package com.pesaguard.backend.rbac.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record GrantRoleRequest(@NotNull UUID userId) {
}