package com.pesaguard.backend.rbac.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.rbac.application.ProductionAccessService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/production-access")
public class ProductionAccessController {

    private final ProductionAccessService productionAccessService;

    public ProductionAccessController(ProductionAccessService productionAccessService) {
        this.productionAccessService = productionAccessService;
    }

    @PostMapping("/projects/{projectId}/requests")
    ResponseEntity<ApiResponse<ProductionAccessRequestView>> request(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody RequestProductionAccessRequest request) {
        return ResponseEntity.status(201)
                .body(ApiResponse.of(productionAccessService.request(principal, projectId, request)));
    }

    @GetMapping("/requests")
    ApiResponse<List<ProductionAccessRequestView>> list(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(productionAccessService.list(principal));
    }

    @PostMapping("/requests/{requestId}/approve")
    ApiResponse<ProductionAccessRequestView> approve(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId,
            @Valid @RequestBody ProductionAccessReviewRequest review) {
        return ApiResponse.of(productionAccessService.approve(principal, requestId, review));
    }

    @PostMapping("/requests/{requestId}/reject")
    ApiResponse<ProductionAccessRequestView> reject(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId,
            @Valid @RequestBody ProductionAccessReviewRequest review) {
        return ApiResponse.of(productionAccessService.reject(principal, requestId, review));
    }

    @PostMapping("/requests/{requestId}/cancel")
    ApiResponse<ProductionAccessRequestView> cancel(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId) {
        return ApiResponse.of(productionAccessService.cancel(principal, requestId));
    }
    /** Claims a request for review. Separate from deciding on it. */
    @PostMapping("/requests/{requestId}/review")
    ApiResponse<ProductionAccessRequestView> beginReview(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId) {
        return ApiResponse.of(productionAccessService.beginReview(principal, requestId));
    }

    /**
     * Brings an approved grant live. Deliberately not implied by approval: this is
     * the step that asserts provisioning succeeded.
     */
    @PostMapping("/requests/{requestId}/activate")
    ApiResponse<ProductionAccessRequestView> activate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId) {
        return ApiResponse.of(productionAccessService.activate(principal, requestId));
    }

    /** Temporarily withdraws a live grant. A reason is required. */
    @PostMapping("/requests/{requestId}/suspend")
    ApiResponse<ProductionAccessRequestView> suspend(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId,
            @RequestParam String reason) {
        return ApiResponse.of(productionAccessService.suspend(principal, requestId, reason));
    }

    /** Returns a suspended grant to service, still bounded by its original expiry. */
    @PostMapping("/requests/{requestId}/reactivate")
    ApiResponse<ProductionAccessRequestView> reactivate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId) {
        return ApiResponse.of(productionAccessService.reactivate(principal, requestId));
    }

    /** Permanently withdraws a grant. Terminal. A reason is required. */
    @PostMapping("/requests/{requestId}/revoke")
    ApiResponse<ProductionAccessRequestView> revoke(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId,
            @RequestParam String reason) {
        return ApiResponse.of(productionAccessService.revoke(principal, requestId, reason));
    }

    /** The full review trail for one request, oldest first. */
    @GetMapping("/requests/{requestId}/history")
    ApiResponse<List<ProductionAccessHistoryView>> history(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID requestId) {
        return ApiResponse.of(productionAccessService.history(principal, requestId));
    }
}
