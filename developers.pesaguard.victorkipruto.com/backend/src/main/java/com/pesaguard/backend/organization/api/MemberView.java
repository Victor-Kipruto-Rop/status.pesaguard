package com.pesaguard.backend.organization.api;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.OrganizationRole;

public record MemberView(
        UUID id,
        UUID userId,
        String email,
        String displayName,
        OrganizationRole role,
        MembershipStatus status,
        Instant createdAt,
        Instant updatedAt,
        List<String> assignedProjects,
        Instant lastActivityAt) {
    public MemberView {
        assignedProjects = assignedProjects == null ? List.of() : List.copyOf(assignedProjects);
    }
}
