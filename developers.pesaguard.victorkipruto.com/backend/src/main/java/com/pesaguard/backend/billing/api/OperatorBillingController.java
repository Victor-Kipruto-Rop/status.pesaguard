package com.pesaguard.backend.billing.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.billing.application.BillingService;
import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/billing")
public class OperatorBillingController {

    private final BillingService billingService;

    public OperatorBillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/invoice-requests")
    ApiResponse<List<BillingInvoiceRequestView>> pendingInvoiceRequests(
            @AuthenticationPrincipal AuthenticatedOperator operator) {
        operator.require(OperatorCapability.BILLING_READ);
        return ApiResponse.of(billingService.pendingInvoiceRequests());
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<BillingInvoiceView> issueInvoice(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody BillingInvoiceRequest request) {
        return ApiResponse.of(billingService.issueInvoice(request, operator, request.reason()));
    }

    @PostMapping("/invoice-requests/{requestId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void declineInvoiceRequest(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID requestId,
            @Valid @RequestBody ManualBillingActionRequest request) {
        billingService.declineInvoiceRequest(requestId, operator, request.reason());
    }

    @PostMapping("/invoices/{invoiceId}/void")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void voidInvoice(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID invoiceId,
            @Valid @RequestBody ManualBillingActionRequest request) {
        billingService.voidInvoice(invoiceId, operator, request.reason());
    }

    @PostMapping("/payments/{paymentId}/manual-confirm")
    ApiResponse<BillingPaymentView> confirmManualPayment(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID paymentId,
            @Valid @RequestBody ManualBillingActionRequest request) {
        return ApiResponse.of(billingService.confirmManualPayment(paymentId, operator, request.reason()));
    }

    @PostMapping("/payments/{paymentId}/reconcile")
    ApiResponse<BillingPaymentView> reconcilePayment(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID paymentId,
            @Valid @RequestBody BillingPaymentReconciliationRequest request) {
        return ApiResponse.of(billingService.reconcilePayment(paymentId, request, operator));
    }
}
