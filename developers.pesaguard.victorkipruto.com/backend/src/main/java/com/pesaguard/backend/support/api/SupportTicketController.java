package com.pesaguard.backend.support.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.support.application.SupportTicketService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@Validated
@RequestMapping("/api/v1/support/tickets")
public class SupportTicketController {

    private final SupportTicketService service;

    public SupportTicketController(SupportTicketService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<SupportTicketView>> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateSupportTicketRequest request) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(service.create(principal, request)));
    }

    @GetMapping
    ApiResponse<SupportTicketPageView> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return ApiResponse.of(service.list(principal, page, pageSize));
    }

    @GetMapping("/{ticketId}")
    ApiResponse<SupportTicketView> get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String ticketId) {
        return ApiResponse.of(service.get(principal, ticketId));
    }

    @PostMapping("/{ticketId}/close")
    ApiResponse<SupportTicketView> close(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String ticketId) {
        return ApiResponse.of(service.close(principal, ticketId));
    }

    @PostMapping("/{ticketId}/reopen")
    ApiResponse<SupportTicketView> reopen(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String ticketId) {
        return ApiResponse.of(service.reopen(principal, ticketId));
    }
}
