package com.pesaguard.backend.securitycenter.api;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.common.api.PageResponse;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.securitycenter.application.SecurityEventService;
import com.pesaguard.backend.securitycenter.domain.SecurityEvent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/security/events")
public class SecurityEventController {

    private final SecurityEventService securityEventService;
    private final AuthorizationService authorizationService;

    public SecurityEventController(SecurityEventService securityEventService,
            AuthorizationService authorizationService) {
        this.securityEventService = securityEventService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/open")
    ApiResponse<PageResponse<SecurityEvent.Record>> open(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        authorizationService.requirePermission(principal, Permission.SECURITY_READ);
        return ApiResponse.of(securityEventService.openFor(principal, page, size));
    }

    @GetMapping("/history")
    ApiResponse<PageResponse<SecurityEvent.Record>> history(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        authorizationService.requirePermission(principal, Permission.SECURITY_READ);
        return ApiResponse.of(securityEventService.historyFor(principal, page, size));
    }

    @PostMapping("/{eventId}/resolve")
    ApiResponse<SecurityEvent.Record> resolve(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID eventId,
            @Valid @RequestBody ResolveSecurityEventRequest request) {
        authorizationService.requirePermission(principal, Permission.SECURITY_CONTROL);
        return ApiResponse.of(securityEventService.resolve(principal, eventId,
                request.resolution(), request.note()));
    }

    public record ResolveSecurityEventRequest(
            @NotNull SecurityEvent.Resolution resolution,
            @NotBlank @Size(max = 1000) String note) {
    }
}
