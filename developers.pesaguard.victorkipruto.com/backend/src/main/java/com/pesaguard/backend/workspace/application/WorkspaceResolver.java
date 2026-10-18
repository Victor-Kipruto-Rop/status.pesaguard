package com.pesaguard.backend.workspace.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.organization.api.OrganizationView;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationMembershipRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Resolves which workspace ("organization") a request is acting on, and refuses
 * the ones the caller has no business touching.
 *
 * <p>Two rules, and the second is the important one:
 *
 * <ul>
 *   <li><b>A workspace is never taken from the request on trust.</b> The id in
 *       the path or the {@code X-Workspace-ID} header is only ever a claim. It is
 *       resolved to a real, active membership of the authenticated user, so a
 *       caller cannot reach another tenant by editing a header or a URL.</li>
 *   <li><b>A miss is indistinguishable from a nonexistent workspace.</b> Both
 *       answer 404. Distinguishing "exists but you are not a member" from "does
 *       not exist" would let an authenticated user enumerate which workspaces
 *       exist on the platform by guessing ids.</li>
 * </ul>
 *
 * <p>Suspended memberships and suspended organizations are refused, so a revoked
 * member keeps no access even if their session has not yet expired.
 */
@Service
public class WorkspaceResolver {

    private final OrganizationMembershipRepository membershipRepository;

    public WorkspaceResolver(OrganizationMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    /**
     * The caller's active membership in a workspace.
     *
     * @throws ResourceNotFoundException when the caller holds no active membership,
     *         or the workspace does not exist — deliberately the same answer
     */
    @Transactional(readOnly = true)
    public OrganizationMembership requireMembership(AuthenticatedUser principal, UUID workspaceId) {
        if (workspaceId == null) {
            throw new ResourceNotFoundException("Workspace");
        }
        return membershipRepository
                .findByOrganizationIdAndUserId(workspaceId, principal.userId())
                .filter(OrganizationMembership::isActive)
                .filter(membership -> membership.getOrganization().getStatus() != OrganizationStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace"));
    }

    /**
     * The caller's principal as it applies within a workspace.
     *
     * <p>Authorities are rebuilt from the membership found <em>here</em> rather
     * than carried over from the session. The session's authorities describe the
     * workspace that session was issued for; reusing them for another workspace
     * would grant that workspace's roles on the strength of a different one's
     * token — which is the whole point of the id being a claim, not a grant.
     */
    @Transactional(readOnly = true)
    public AuthenticatedUser principalFor(AuthenticatedUser principal, UUID workspaceId) {
        OrganizationMembership membership = requireMembership(principal, workspaceId);
        return new AuthenticatedUser(
                principal.userId(),
                workspaceId,
                principal.sessionId(),
                principal.email(),
                principal.displayName(),
                java.util.Set.of("ROLE_" + membership.getRole().name()),
                membership.getOrganization().getStatus());
    }

    /**
     * The workspace a request is scoped to.
     *
     * <p>Accepts {@code X-Workspace-ID} as a claim, never as authority: whatever
     * the header says is still resolved through {@link #principalFor}, so a forged
     * or stale header selects nothing rather than granting access. Absent header
     * means the session's own workspace, which is how every existing client
     * already works.
     *
     * @param header the raw header value, or null/blank when not sent
     */
    @Transactional(readOnly = true)
    public AuthenticatedUser principalForHeader(AuthenticatedUser principal, String header) {
        if (header == null || header.isBlank()) {
            return principal;
        }
        UUID claimed;
        try {
            claimed = UUID.fromString(header.trim());
        } catch (IllegalArgumentException malformed) {
            // Not an id we could ever have issued. Refused as "no such workspace"
            // rather than as a parse error, so a malformed header cannot be used to
            // distinguish validation behaviour from authorization behaviour.
            throw new ResourceNotFoundException("Workspace");
        }
        if (claimed.equals(principal.organizationId())) {
            return principal;
        }
        return principalFor(principal, claimed);
    }

    /** Every workspace the caller can currently act in. */
    @Transactional(readOnly = true)
    public List<Organization> membershipsFor(AuthenticatedUser principal) {
        return membershipRepository.findAllActiveByUserId(principal.userId()).stream()
                .map(OrganizationMembership::getOrganization)
                .filter(organization -> organization.getStatus() != OrganizationStatus.DELETED)
                .toList();
    }

    /** The caller's role in a workspace, for building a scoped view. */
    @Transactional(readOnly = true)
    public OrganizationRole roleIn(AuthenticatedUser principal, UUID workspaceId) {
        return requireMembership(principal, workspaceId).getRole();
    }
}