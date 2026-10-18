package com.pesaguard.backend.organization.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.organization.application.OrganizationLifecycleService;
import com.pesaguard.backend.organization.application.OrganizationMembershipService;
import com.pesaguard.backend.organization.application.OrganizationSecuritySettingsService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/organization")
public class OrganizationController {

    private final OrganizationLifecycleService lifecycleService;
    private final OrganizationMembershipService membershipService;
    private final OrganizationSecuritySettingsService securitySettingsService;

    public OrganizationController(
            OrganizationLifecycleService lifecycleService,
            OrganizationMembershipService membershipService,
            OrganizationSecuritySettingsService securitySettingsService) {
        this.lifecycleService = lifecycleService;
        this.membershipService = membershipService;
        this.securitySettingsService = securitySettingsService;
    }

    @GetMapping
    ApiResponse<OrganizationView> current(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(lifecycleService.current(principal));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ResponseEntity<ApiResponse<OrganizationView>> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateOrganizationRequest request) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(lifecycleService.create(principal, request)));
    }

    @PatchMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<OrganizationView> update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateOrganizationRequest request) {
        return ApiResponse.of(lifecycleService.update(principal, request));
    }

    @PostMapping("/verify")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<OrganizationView> verify(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody VerifyOrganizationRequest request) {
        return ApiResponse.of(lifecycleService.verify(principal, request));
    }

    @PostMapping("/suspend")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<OrganizationView> suspend(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(lifecycleService.suspend(principal));
    }

    @PostMapping("/restore")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<OrganizationView> restore(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(lifecycleService.restore(principal));
    }

    @PostMapping("/disable")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<OrganizationView> disable(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(lifecycleService.disable(principal));
    }

    @DeleteMapping
    @PreAuthorize("hasRole('OWNER')")
    ApiResponse<OrganizationView> delete(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(lifecycleService.delete(principal));
    }

    @PostMapping("/ownership/transfer")
    @PreAuthorize("hasRole('OWNER')")
    ApiResponse<OrganizationView> transferOwnership(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody TransferOwnershipRequest request) {
        return ApiResponse.of(lifecycleService.transferOwnership(principal, request));
    }

    @GetMapping("/security-settings")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<OrganizationSecuritySettingsView> securitySettings(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(securitySettingsService.get(principal));
    }

    @PutMapping("/security-settings")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<OrganizationSecuritySettingsView> updateSecuritySettings(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateSecuritySettingsRequest request) {
        return ApiResponse.of(securitySettingsService.update(principal, request));
    }

    @GetMapping("/members")
    ApiResponse<List<MemberView>> members(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(membershipService.members(principal));
    }

    @GetMapping("/members/{membershipId}/history")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<List<MembershipHistoryView>> memberHistory(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID membershipId) {
        return ApiResponse.of(membershipService.history(principal, membershipId));
    }

    @PatchMapping("/members/{membershipId}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ApiResponse<MemberView> updateMemberStatus(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID membershipId,
            @Valid @RequestBody UpdateMemberStatusRequest request) {
        return ApiResponse.of(membershipService.updateStatus(principal, membershipId, request));
    }

    @PatchMapping("/members/{membershipId}/role")
    @PreAuthorize("hasRole('OWNER')")
    ApiResponse<MemberView> updateMemberRole(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID membershipId,
            @Valid @RequestBody ChangeMemberRoleRequest request) {
        return ApiResponse.of(membershipService.updateRole(principal, membershipId, request));
    }

    @PostMapping("/invitations")
    ResponseEntity<ApiResponse<CreatedInvitationView>> invite(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateInvitationRequest request) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(membershipService.invite(principal, request, idempotencyKey)));
    }

    @GetMapping("/invitations")
    ApiResponse<List<InvitationView>> invitations(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(membershipService.invitations(principal));
    }

    @PostMapping("/invitations/{invitationId}/resend")
    ResponseEntity<ApiResponse<CreatedInvitationView>> resendInvitation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID invitationId) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(membershipService.resendInvitation(principal, invitationId)));
    }

    @DeleteMapping("/invitations/{invitationId}")
    ResponseEntity<Void> revokeInvitation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID invitationId) {
        membershipService.revokeInvitation(principal, invitationId);
        return ResponseEntity.noContent().build();
    }

}
