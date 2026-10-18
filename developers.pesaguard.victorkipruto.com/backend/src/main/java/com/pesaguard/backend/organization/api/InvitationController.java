package com.pesaguard.backend.organization.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.organization.application.OrganizationMembershipService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@Validated
@RequestMapping("/api/v1/invitations")
public class InvitationController {

    private final OrganizationMembershipService membershipService;

    public InvitationController(OrganizationMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @GetMapping("/{token}/preview")
    ResponseEntity<ApiResponse<InvitationPreviewView>> preview(
            @PathVariable @NotBlank @Size(max = 128) String token,
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(ApiResponse.of(membershipService.previewInvitation(token, request.getRemoteAddr())));
    }

    @PostMapping("/{token}/accept")
    ResponseEntity<ApiResponse<AcceptedInvitationView>> accept(
            @PathVariable @NotBlank @Size(max = 128) String token,
            @AuthenticationPrincipal AuthenticatedUser principal,
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(membershipService.accept(
                        new AcceptInvitationRequest(token), principal, request.getRemoteAddr())));
    }

    @PostMapping("/{token}/decline")
    ResponseEntity<Void> decline(
            @PathVariable @NotBlank @Size(max = 128) String token,
            @AuthenticationPrincipal AuthenticatedUser principal,
            HttpServletRequest request) {
        membershipService.decline(token, principal, request.getRemoteAddr());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
