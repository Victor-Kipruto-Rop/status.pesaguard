package com.pesaguard.backend.organization.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptInvitationRequest(
        @NotBlank @Size(max = 512) String token) {
}