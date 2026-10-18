package com.pesaguard.backend.security.authentication;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth/username")
public class UsernameController {

    private final UsernameService usernameService;

    public UsernameController(UsernameService usernameService) {
        this.usernameService = usernameService;
    }

    @GetMapping
    ApiResponse<UsernameResponse> get(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(usernameService.get(principal));
    }

    @PatchMapping
    ResponseEntity<ApiResponse<UsernameResponse>> update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateUsernameRequest request) {
        return ResponseEntity.ok().body(ApiResponse.of(usernameService.update(principal, request)));
    }
}
