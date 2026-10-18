package com.pesaguard.backend.project.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record TransferProjectOwnershipRequest(@NotNull UUID userId) {
}