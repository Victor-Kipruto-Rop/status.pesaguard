package com.pesaguard.backend.organization.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyOrganizationRequest(
        @NotBlank @Size(max = 120) String reference) {
}