package com.pesaguard.backend.developerpreferences.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.developerpreferences.application.DeveloperPreferencesService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/developer/preferences")
public class DeveloperPreferencesController {

    private final DeveloperPreferencesService preferencesService;

    public DeveloperPreferencesController(DeveloperPreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    @GetMapping
    ApiResponse<DeveloperPreferencesView> get(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(preferencesService.get(principal));
    }

    @PutMapping
    ApiResponse<DeveloperPreferencesView> update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateDeveloperPreferencesRequest request) {
        return ApiResponse.of(preferencesService.update(principal, request));
    }

    @PatchMapping("/appearance")
    ApiResponse<DeveloperPreferencesView> updateAppearance(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateAppearanceRequest request) {
        return ApiResponse.of(preferencesService.updateAppearance(principal, request));
    }
}
