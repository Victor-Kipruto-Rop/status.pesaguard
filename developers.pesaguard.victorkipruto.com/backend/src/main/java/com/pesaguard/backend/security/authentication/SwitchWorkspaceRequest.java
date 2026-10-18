package com.pesaguard.backend.security.authentication;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record SwitchWorkspaceRequest(@NotNull UUID workspaceId) {
}
