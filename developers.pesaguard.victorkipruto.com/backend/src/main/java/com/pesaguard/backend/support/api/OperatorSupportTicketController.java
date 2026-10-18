package com.pesaguard.backend.support.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;
import com.pesaguard.backend.support.application.SupportTicketService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@Validated
@RequestMapping("/internal/support/tickets")
public class OperatorSupportTicketController {

    private final SupportTicketService service;

    public OperatorSupportTicketController(SupportTicketService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<OperatorSupportTicketPageView> list(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        operator.require(OperatorCapability.SUPPORT_READ);
        return ApiResponse.of(service.listForOperator(operator, page, pageSize));
    }

    @PostMapping("/{ticketId}/resolve")
    @ResponseStatus(HttpStatus.OK)
    ApiResponse<OperatorSupportTicketView> resolve(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable String ticketId,
            @Valid @RequestBody ResolveSupportTicketRequest request) {
        operator.require(OperatorCapability.SUPPORT_RESOLVE);
        return ApiResponse.of(service.resolveForOperator(operator, ticketId, request.note()));
    }

    record ResolveSupportTicketRequest(@NotBlank @Size(max = 1000) String note) {
    }
}
