package com.pesaguard.backend.scopes.api;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.scopes.application.AccessDecisionService;
import com.pesaguard.backend.scopes.application.ScopeRegistryService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * The scope registry and per-credential scope grants.
 *
 * <p>All endpoints require a portal session and a credential permission. The
 * registry itself is readable with {@code credential:read}; changing it requires
 * {@code credential:update}, which is why annotating a scope is a deliberate,
 * permissioned act rather than a side effect of editing a key.
 */
@RestController
@RequestMapping("/api/v1/scopes")
public class ScopeController {

    private final ScopeRegistryService registry;
    private final AccessDecisionService decisionService;

    public ScopeController(ScopeRegistryService registry, AccessDecisionService decisionService) {
        this.registry = registry;
        this.decisionService = decisionService;
    }

    /** The full registry. Deprecated scopes are hidden unless asked for. */
    @GetMapping
    ApiResponse<List<ScopeView>> catalog(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "false") boolean includeDeprecated) {
        return ApiResponse.of(registry.catalog(principal, includeDeprecated));
    }

    @GetMapping("/{scopeName}")
    ApiResponse<ScopeView> get(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String scopeName) {
        return ApiResponse.of(registry.get(principal, scopeName));
    }

    /** Grants scopes to a credential, recording who granted each and at which version. */
    @PostMapping("/assignments")
    ApiResponse<List<ScopeAssignmentView>> assign(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam UUID apiKeyId,
            @Valid @RequestBody AssignScopesRequest request) {
        return ApiResponse.of(registry.assign(principal, apiKeyId, request.scopes()));
    }

    @GetMapping("/assignments")
    ApiResponse<List<ScopeAssignmentView>> assignments(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam UUID apiKeyId) {
        return ApiResponse.of(registry.assignmentsFor(principal, apiKeyId));
    }

    @DeleteMapping("/assignments/{assignmentId}")
    ApiResponse<Void> revokeAssignment(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID assignmentId,
            @RequestParam(required = false) String reason) {
        registry.revokeAssignment(principal, assignmentId, reason);
        return ApiResponse.of(null);
    }

    /**
     * Deprecates a scope. The scope keeps working; callers are told what to
     * migrate to. Revocation is a separate, explicit act.
     */
    @PostMapping("/{scopeName}/deprecate")
    ApiResponse<ScopeView> deprecate(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String scopeName,
            @Valid @RequestBody DeprecateScopeRequest request) {
        return ApiResponse.of(registry.deprecate(principal, scopeName,
                request.replacedBy(), request.reason()));
    }

    /** Restricts a scope further. A reason is required. */
    @PostMapping("/{scopeName}/restrict")
    ApiResponse<ScopeView> restrict(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String scopeName,
            @RequestParam(required = false) String reason) {
        return ApiResponse.of(registry.restrict(principal, scopeName, reason));
    }

    /**
     * Recent denials for this organization. Exposed because "why was my key
     * refused?" should be answerable from the product, not only from the database.
     */
    @GetMapping("/decisions/denied")
    ApiResponse<List<AccessDecisionView>> recentDenials(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.of(decisionService.recentDenials(principal, limit).stream()
                .map(AccessDecisionView::from)
                .toList());
    }
}