package com.pesaguard.backend.organization.api;

import com.pesaguard.backend.organization.domain.MembershipStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateMemberStatusRequest(
        @NotNull MembershipStatus status,
        @Size(max = 500) String reason) {
}