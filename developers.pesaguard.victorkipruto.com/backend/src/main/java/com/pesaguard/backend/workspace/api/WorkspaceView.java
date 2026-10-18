package com.pesaguard.backend.workspace.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.domain.OrganizationType;

/**
 * A workspace as the portal sees it.
 *
 * <p>Carries the caller's own {@code role} alongside the workspace fields. The role
 * is per-caller, not a property of the workspace, and a client that cannot see it
 * has to hard-code role-to-capability rules that will drift from the server.
 *
 * @param current true when this is the workspace the current session is bound to,
 *        so the portal can mark the active one without a second request
 */
public record WorkspaceView(
        UUID id,
        String name,
        String slug,
        OrganizationType type,
        OrganizationStatus status,
        UUID ownerUserId,
        OrganizationRole role,
        boolean current,
        Instant createdAt,
        Instant updatedAt) {
}