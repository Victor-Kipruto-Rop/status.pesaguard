package com.pesaguard.backend.organization.api;

import com.pesaguard.backend.organization.domain.OrganizationRole;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateInvitationRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotNull OrganizationRole role) {

    public CreateInvitationRequest {
        if (role == OrganizationRole.OWNER) {
            throw new IllegalArgumentException("Ownership is transferred explicitly, not by invitation");
        }
    }
}