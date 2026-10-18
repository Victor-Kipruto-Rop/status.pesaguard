package com.pesaguard.backend.billing.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.billing.application.BillingService;
import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping
    ApiResponse<BillingOverviewView> overview(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(billingService.overview(principal));
    }

    @PostMapping("/invoice-requests")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<BillingInvoiceRequestView> requestInvoice(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody BillingInvoiceRequestCreate request) {
        return ApiResponse.of(billingService.requestManualInvoice(principal, request));
    }

    @PostMapping("/invoices/{invoiceId}/payments")
    ApiResponse<BillingPaymentView> startPayment(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID invoiceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody StartBillingPaymentRequest request) {
        return ApiResponse.of(billingService.startPayment(principal, invoiceId, request, idempotencyKey));
    }

    @PostMapping("/payments/{paymentId}/refresh")
    ApiResponse<BillingPaymentView> refreshPayment(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID paymentId) {
        return ApiResponse.of(billingService.refreshPayment(principal, paymentId));
    }

    @PostMapping("/payments/{paymentId}/cancel")
    ApiResponse<BillingPaymentView> cancelManualPayment(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID paymentId) {
        return ApiResponse.of(billingService.cancelManualPayment(principal, paymentId));
    }

    @PostMapping("/payments/{paymentId}/retry")
    ApiResponse<BillingPaymentView> retryStripeCheckout(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID paymentId) {
        return ApiResponse.of(billingService.retryStripeCheckout(principal, paymentId));
    }

    @PostMapping("/webhooks/stripe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void stripeWebhook(@RequestBody byte[] body, @RequestHeader("Stripe-Signature") String signature) {
        billingService.acceptStripeWebhook(body, signature);
    }

    @PostMapping("/webhooks/payhero")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void payHeroCallback(@RequestBody byte[] body) {
        billingService.acceptPayHeroCallback(body);
    }

    @PostMapping("/webhooks/daraja")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void darajaCallback(@RequestBody byte[] body) {
        billingService.acceptDarajaCallback(body);
    }

    @PostMapping("/webhooks/airtel-money")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void airtelMoneyCallback(@RequestBody byte[] body) {
        billingService.acceptAirtelCallback(body);
    }
}
