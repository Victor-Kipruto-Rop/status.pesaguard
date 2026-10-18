package com.pesaguard.backend.rbac.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.rbac.application.AuthorizationService;
import com.pesaguard.backend.rbac.application.RoleManagementService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/rbac")
public class RbacController {

    private final RoleManagementService roleManagementService;
    private final AuthorizationService authorizationService;

    public RbacController(RoleManagementService roleManagementService,
            AuthorizationService authorizationService) {
        this.roleManagementService = roleManagementService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/permissions")
    ApiResponse<List<String>> permissions() {
        return ApiResponse.of(authorizationService.catalogValues());
    }

    @GetMapping("/permissions/effective")
    ApiResponse<List<String>> effectivePermissions(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(authorizationService.effectivePermissions(principal).stream()
                .map(permission -> permission.value())
                .sorted()
                .toList());
    }

    @GetMapping("/roles/built-in")
    ApiResponse<List<RoleView>> builtInRoles() {
        return ApiResponse.of(roleManagementService.listBuiltIn());
    }

    @GetMapping("/roles")
    ApiResponse<List<RoleView>> roles(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(roleManagementService.list(principal));
    }

    @PostMapping("/roles")
    ApiResponse<RoleView> createRole(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.of(roleManagementService.create(principal, request));
    }

    @PatchMapping("/roles/{roleId}")
    ApiResponse<RoleView> updateRole(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID roleId, @Valid @RequestBody UpdateRoleRequest request) {
        return ApiResponse.of(roleManagementService.update(principal, roleId, request));
    }

    @PostMapping("/roles/{roleId}/archive")
    ApiResponse<RoleView> archiveRole(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID roleId) {
        return ApiResponse.of(roleManagementService.archive(principal, roleId));
    }

    @PostMapping("/roles/{roleId}/restore")
    ApiResponse<RoleView> restoreRole(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID roleId) {
        return ApiResponse.of(roleManagementService.restore(principal, roleId));
    }

    @GetMapping("/role-assignments")
    ApiResponse<List<RoleAssignmentView>> assignments(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(roleManagementService.assignments(principal));
    }

    @PostMapping("/roles/{roleId}/assignments")
    ApiResponse<RoleAssignmentView> grantRole(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID roleId, @Valid @RequestBody GrantRoleRequest request) {
        return ApiResponse.of(roleManagementService.grant(principal, roleId, request));
    }

    @DeleteMapping("/role-assignments/{assignmentId}")
    ResponseEntity<Void> revokeRole(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID assignmentId) {
        roleManagementService.revokeAssignment(principal, assignmentId);
        return ResponseEntity.noContent().build();
    }
}