package com.pesaguard.backend.organization.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record TransferOwnershipRequest(@NotNull UUID membershipId) {
}