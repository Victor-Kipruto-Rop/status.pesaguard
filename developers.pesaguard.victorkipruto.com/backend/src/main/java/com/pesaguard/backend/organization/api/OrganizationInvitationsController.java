package com.pesaguard.backend.organization.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.organization.application.OrganizationMembershipService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/invitations")
public class OrganizationInvitationsController {

    private final OrganizationMembershipService membershipService;

    public OrganizationInvitationsController(OrganizationMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping
    ResponseEntity<ApiResponse<CreatedInvitationView>> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID organizationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateInvitationRequest request) {
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(membershipService.invite(
                        principal, organizationId, request, idempotencyKey)));
    }

    @GetMapping
    ResponseEntity<ApiResponse<List<InvitationView>>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID organizationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(membershipService.invitations(principal, organizationId)));
    }

    @GetMapping("/{invitationId}")
    ResponseEntity<ApiResponse<InvitationView>> get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID invitationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(membershipService.invitation(principal, organizationId, invitationId)));
    }

    @PostMapping("/{invitationId}/resend")
    ResponseEntity<ApiResponse<CreatedInvitationView>> resend(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID invitationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(membershipService.resendInvitation(principal, organizationId, invitationId)));
    }

    @PostMapping("/{invitationId}/revoke")
    ResponseEntity<Void> revoke(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID invitationId) {
        membershipService.revokeInvitation(principal, organizationId, invitationId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/{invitationId}/cancel")
    ResponseEntity<Void> cancel(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID invitationId) {
        membershipService.cancelInvitation(principal, organizationId, invitationId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
