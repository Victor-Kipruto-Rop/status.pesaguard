package com.pesaguard.backend.organization.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMemberRequest(
        @NotBlank @Size(min = 2, max = 120) String displayName) {
}