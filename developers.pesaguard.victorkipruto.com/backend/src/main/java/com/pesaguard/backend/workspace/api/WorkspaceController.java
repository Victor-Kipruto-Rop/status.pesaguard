package com.pesaguard.backend.workspace.api;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.audit.api.AuditEventView;
import com.pesaguard.backend.audit.application.AuditQueryService;
import com.pesaguard.backend.audit.domain.AuditEvent;
import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.common.api.PageResponse;
import com.pesaguard.backend.organization.api.ChangeMemberRoleRequest;
import com.pesaguard.backend.organization.api.CreateInvitationRequest;
import com.pesaguard.backend.organization.api.CreatedInvitationView;
import com.pesaguard.backend.organization.api.MemberView;
import com.pesaguard.backend.organization.api.OrganizationSecuritySettingsView;
import com.pesaguard.backend.organization.api.OrganizationView;
import com.pesaguard.backend.organization.api.UpdateMemberStatusRequest;
import com.pesaguard.backend.organization.api.UpdateOrganizationRequest;
import com.pesaguard.backend.organization.api.UpdateSecuritySettingsRequest;
import com.pesaguard.backend.organization.application.OrganizationLifecycleService;
import com.pesaguard.backend.organization.application.OrganizationMembershipService;
import com.pesaguard.backend.organization.application.OrganizationSecuritySettingsService;
import com.pesaguard.backend.organization.domain.MembershipStatus;
import com.pesaguard.backend.organization.domain.Organization;
import com.pesaguard.backend.organization.domain.OrganizationMembership;
import com.pesaguard.backend.organization.domain.OrganizationRole;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.workspace.application.WorkspaceResolver;

import jakarta.validation.Valid;

/**
 * Workspace-scoped API.
 *
 * <p>These routes address a workspace by id in the path, where the existing
 * {@code /api/v1/organization} routes address only the session's own workspace.
 * Both are supported: the singular routes are unchanged and stay the simplest thing
 * for a single-workspace client.
 *
 * <p>Every handler resolves its workspace through {@link WorkspaceResolver} and
 * acts on a principal rebuilt from the membership found there, so the
 * {@code @PreAuthorize} checks run against the <em>target</em> workspace's role
 * rather than the session's. A caller who is OWNER in one workspace and VIEWER in
 * another gets each workspace's own answer, not their strongest one.
 *
 * <p>{@code X-Workspace-ID} is accepted as a claim, never as authority: see
 * {@link WorkspaceResolver#principalForHeader}.
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    static final String WORKSPACE_HEADER = "X-Workspace-ID";

    private final WorkspaceResolver workspaceResolver;
    private final OrganizationLifecycleService lifecycleService;
    private final OrganizationMembershipService membershipService;
    private final OrganizationSecuritySettingsService securitySettingsService;
    private final AuditQueryService auditQueryService;

    public WorkspaceController(
            WorkspaceResolver workspaceResolver,
            OrganizationLifecycleService lifecycleService,
            OrganizationMembershipService membershipService,
            OrganizationSecuritySettingsService securitySettingsService,
            AuditQueryService auditQueryService) {
        this.workspaceResolver = workspaceResolver;
        this.lifecycleService = lifecycleService;
        this.membershipService = membershipService;
        this.securitySettingsService = securitySettingsService;
        this.auditQueryService = auditQueryService;
    }

    /** Every workspace the caller can act in. Scoped to the caller, never to a parameter. */
    @GetMapping
    ApiResponse<List<WorkspaceView>> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(workspaceResolver.membershipsFor(principal).stream()
                .map(workspace -> toView(workspace, principal,
                        workspaceResolver.roleIn(principal, workspace.getId())))
                .toList());
    }

    @GetMapping("/{workspaceId}")
    ApiResponse<WorkspaceView> get(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId) {
        return ApiResponse.of(toView(workspaceResolver.requireMembership(principal, workspaceId),
                principal, workspaceResolver.roleIn(principal, workspaceId)));
    }

    /** Renames a workspace. Requires update permission <em>in that workspace</em>. */
    @PatchMapping("/{workspaceId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<WorkspaceView> update(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateOrganizationRequest request) {
        AuthenticatedUser scoped = workspaceResolver.principalFor(principal, workspaceId);
        OrganizationView updated = lifecycleService.update(scoped, request);
        return ApiResponse.of(toView(updated, scoped));
    }

    @GetMapping("/{workspaceId}/members")
    ApiResponse<List<MemberView>> members(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId) {
        return ApiResponse.of(membershipService.members(
                workspaceResolver.principalFor(principal, workspaceId)));
    }

    @PatchMapping("/{workspaceId}/members/{memberId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<MemberView> updateMemberStatus(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId, @PathVariable UUID memberId,
            @Valid @RequestBody UpdateMemberStatusRequest request) {
        return ApiResponse.of(membershipService.updateStatus(
                workspaceResolver.principalFor(principal, workspaceId), memberId, request));
    }

    /**
     * Removes a member.
     *
     * <p>A revocation rather than a row delete: "who was removed, when, and by whom"
     * is the same question the membership history table exists to answer, and a
     * deleted row answers it with silence.
     */
    @DeleteMapping("/{workspaceId}/members/{memberId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ResponseEntity<Void> removeMember(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId, @PathVariable UUID memberId) {
        membershipService.updateStatus(workspaceResolver.principalFor(principal, workspaceId),
                memberId, new UpdateMemberStatusRequest(MembershipStatus.REVOKED,
                        "Removed via workspace API"));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{workspaceId}/members/{memberId}/role")
    @PreAuthorize("hasRole('OWNER')")
    ApiResponse<MemberView> updateMemberRole(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId, @PathVariable UUID memberId,
            @Valid @RequestBody ChangeMemberRoleRequest request) {
        return ApiResponse.of(membershipService.updateRole(
                workspaceResolver.principalFor(principal, workspaceId), memberId, request));
    }

    @PostMapping("/{workspaceId}/invitations")
    ResponseEntity<ApiResponse<CreatedInvitationView>> invite(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateInvitationRequest request) {
        CreatedInvitationView created = membershipService.invite(
                workspaceResolver.principalFor(principal, workspaceId), request, idempotencyKey);
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    /**
     * A workspace's audit trail.
     *
     * <p>Serves the same hash-chained events as {@code /audit-events}, narrowed to
     * the workspace in the path. Read permission is enforced inside the audit
     * service against the scoped principal, so a role without audit rights is
     * refused outright rather than quietly handed an empty page.
     */
    @GetMapping("/{workspaceId}/activity")
    ApiResponse<PageResponse<AuditEventView>> activity(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<AuditEvent> result = auditQueryService.list(
                workspaceResolver.principalFor(principal, workspaceId), page, size);
        List<AuditEventView> items = result.getContent().stream()
                .map(event -> new AuditEventView(event.getId(), event.getSequenceNumber(),
                        event.getActorUserId(), event.getAction(), event.getResourceType(),
                        event.getResourceId(), event.getRequestId(), event.getCorrelationId(),
                        event.getIpAddress(), event.getUserAgent(), event.getHashVersion(),
                        event.getMetadata(), event.getPreviousHash(), event.getEventHash(),
                        event.getCreatedAt()))
                .toList();
        return ApiResponse.of(PageResponse.of(items, result.getNumber(), result.getSize(),
                result.getTotalElements()));
    }

    @GetMapping("/{workspaceId}/settings")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<OrganizationSecuritySettingsView> settings(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId) {
        return ApiResponse.of(securitySettingsService.get(
                workspaceResolver.principalFor(principal, workspaceId)));
    }

    /**
     * Updates settings. {@code PATCH}, as specified.
     *
     * <p>Delegates to a service that persists a whole settings value, so this is
     * read-modify-write: a field the caller omits is reset, not preserved. That
     * differs from true PATCH semantics and is called out because a client reusing
     * the old PUT body unchanged would blank the settings it did not send.
     */
    @PatchMapping("/{workspaceId}/settings")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<OrganizationSecuritySettingsView> updateSettings(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateSecuritySettingsRequest request) {
        return ApiResponse.of(securitySettingsService.update(
                workspaceResolver.principalFor(principal, workspaceId), request));
    }

    /**
     * The workspace named by {@code X-Workspace-ID}, or the session's own.
     *
     * <p>Lets a client carry workspace context on requests that have no workspace
     * in the path. The header is a claim: an unknown workspace, a workspace the
     * caller is not a member of, and a malformed value all answer 404 alike.
     */
    @GetMapping("/current")
    ApiResponse<WorkspaceView> current(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(name = WORKSPACE_HEADER, required = false) String workspaceHeader) {
        AuthenticatedUser scoped = workspaceResolver.principalForHeader(principal, workspaceHeader);
        OrganizationMembership membership =
                workspaceResolver.requireMembership(principal, scoped.organizationId());
        return ApiResponse.of(toView(membership, scoped, membership.getRole()));
    }

    private WorkspaceView toView(Organization workspace, AuthenticatedUser caller, OrganizationRole role) {
        return new WorkspaceView(
                workspace.getId(), workspace.getName(), workspace.getSlug(),
                workspace.getOrganizationType(), workspace.getStatus(), workspace.getOwnerUserId(),
                role, workspace.getId().equals(caller.organizationId()),
                workspace.getCreatedAt(), workspace.getUpdatedAt());
    }

    private WorkspaceView toView(OrganizationMembership membership, AuthenticatedUser caller,
            OrganizationRole role) {
        return toView(membership.getOrganization(), caller, role);
    }

    private WorkspaceView toView(OrganizationView view, AuthenticatedUser scoped) {
        return new WorkspaceView(
                view.id(), view.name(), view.slug(), view.type(), view.status(), view.ownerUserId(),
                workspaceResolver.roleIn(scoped, view.id()),
                view.id().equals(scoped.organizationId()),
                view.createdAt(), view.updatedAt());
    }
}