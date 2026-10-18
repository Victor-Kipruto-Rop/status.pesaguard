package com.pesaguard.backend.project.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.common.api.PageResponse;
import com.pesaguard.backend.project.application.ProjectMembershipService;
import com.pesaguard.backend.project.application.ProjectService;
import com.pesaguard.backend.project.application.ProjectSettingsService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectMembershipService projectMembershipService;
    private final ProjectSettingsService projectSettingsService;

    public ProjectController(
            ProjectService projectService,
            ProjectMembershipService projectMembershipService,
            ProjectSettingsService projectSettingsService) {
        this.projectService = projectService;
        this.projectMembershipService = projectMembershipService;
        this.projectSettingsService = projectSettingsService;
    }

    @PostMapping("/{projectId}/archive")
    ApiResponse<ProjectView> archive(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId) {
        return ApiResponse.of(projectService.archive(principal, projectId));
    }

    @PostMapping("/{projectId}/restore")
    ApiResponse<ProjectView> restore(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId) {
        return ApiResponse.of(projectService.restore(principal, projectId));
    }

    @PostMapping("/{projectId}/deactivate")
    ApiResponse<ProjectView> deactivate(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId) {
        return ApiResponse.of(projectService.deactivate(principal, projectId));
    }

    @PostMapping("/{projectId}/activate")
    ApiResponse<ProjectView> activate(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId) {
        return ApiResponse.of(projectService.activate(principal, projectId));
    }

    @PatchMapping("/{projectId}")
    ApiResponse<ProjectView> update(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @Valid @RequestBody UpdateProjectRequest request) {
        return ApiResponse.of(projectService.update(principal, projectId, request));
    }

    @PostMapping("/{projectId}/ownership/transfer")
    ApiResponse<ProjectView> transferOwnership(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @Valid @RequestBody TransferProjectOwnershipRequest request) {
        return ApiResponse.of(projectService.transferOwnership(principal, projectId, request));
    }

    @GetMapping("/{projectId}/members")
    ApiResponse<List<ProjectMemberView>> members(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId) {
        return ApiResponse.of(projectMembershipService.list(principal, projectId));
    }

    @PostMapping("/{projectId}/members")
    ApiResponse<ProjectMemberView> addMember(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @Valid @RequestBody AddProjectMemberRequest request) {
        return ApiResponse.of(projectMembershipService.add(principal, projectId, request));
    }

    @PatchMapping("/{projectId}/members/{memberId}/role")
    ApiResponse<ProjectMemberView> changeMemberRole(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID memberId,
            @Valid @RequestBody ChangeProjectMemberRoleRequest request) {
        return ApiResponse.of(projectMembershipService.changeRole(principal, projectId, memberId, request));
    }

    @DeleteMapping("/{projectId}/members/{memberId}")
    ResponseEntity<Void> revokeMember(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @PathVariable UUID memberId) {
        projectMembershipService.revoke(principal, projectId, memberId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{projectId}/settings")
    ApiResponse<ProjectSettingsView> settings(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId) {
        return ApiResponse.of(projectSettingsService.get(principal, projectId));
    }

    @PutMapping("/{projectId}/settings")
    ApiResponse<ProjectSettingsView> updateSettings(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId, @Valid @RequestBody UpdateProjectSettingsRequest request) {
        return ApiResponse.of(projectSettingsService.update(principal, projectId, request));
    }

    @PostMapping
    ApiResponse<ProjectView> create(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.of(projectService.create(principal, request));
    }

    @GetMapping
    ApiResponse<PageResponse<ProjectView>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.of(projectService.list(principal, page, size));
    }

    @GetMapping("/{projectId}")
    ApiResponse<ProjectView> get(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId) {
        return ApiResponse.of(projectService.get(principal, projectId));
    }
}
