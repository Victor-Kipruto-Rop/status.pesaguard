package com.pesaguard.backend.billing.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.billing.api.BillingInvoiceRequestCreate;
import com.pesaguard.backend.billing.api.BillingInvoiceRequestView;
import com.pesaguard.backend.billing.api.BillingInvoiceView;
import com.pesaguard.backend.billing.api.BillingOverviewView;
import com.pesaguard.backend.billing.api.BillingPaymentView;
import com.pesaguard.backend.billing.api.BillingPaymentReconciliationRequest;
import com.pesaguard.backend.billing.api.BillingProviderOption;
import com.pesaguard.backend.billing.api.StartBillingPaymentRequest;
import com.pesaguard.backend.billing.domain.BillingInvoice;
import com.pesaguard.backend.billing.domain.BillingInvoiceRequest;
import com.pesaguard.backend.billing.domain.BillingPayment;
import com.pesaguard.backend.billing.infrastructure.BillingInvoiceRepository;
import com.pesaguard.backend.billing.infrastructure.BillingInvoiceRequestRepository;
import com.pesaguard.backend.billing.infrastructure.BillingPaymentRepository;
import com.pesaguard.backend.billing.infrastructure.BillingWebhookEventRepository;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.organization.domain.OrganizationStatus;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import tools.jackson.databind.JsonNode;

@Service
public class BillingService {

    private final BillingInvoiceRepository invoiceRepository;
    private final BillingInvoiceRequestRepository invoiceRequestRepository;
    private final BillingPaymentRepository paymentRepository;
    private final BillingPaymentPersistence paymentPersistence;
    private final BillingWebhookEventRepository webhookEventRepository;
    private final OrganizationRepository organizationRepository;
    private final BillingGateway gateway;
    private final CredentialCryptoService crypto;
    private final Clock clock;

    public BillingService(BillingInvoiceRepository invoiceRepository,
            BillingInvoiceRequestRepository invoiceRequestRepository,
            BillingPaymentRepository paymentRepository, BillingPaymentPersistence paymentPersistence,
            BillingWebhookEventRepository webhookEventRepository,
            OrganizationRepository organizationRepository, BillingGateway gateway,
            CredentialCryptoService crypto, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceRequestRepository = invoiceRequestRepository;
        this.paymentRepository = paymentRepository;
        this.paymentPersistence = paymentPersistence;
        this.webhookEventRepository = webhookEventRepository;
        this.organizationRepository = organizationRepository;
        this.gateway = gateway;
        this.crypto = crypto;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BillingOverviewView overview(AuthenticatedUser principal) {
        UUID organizationId = principal.organizationId();
        return new BillingOverviewView(
                invoiceRepository.findByOrganizationIdOrderByIssuedAtDesc(organizationId)
                        .stream().map(BillingService::toView).toList(),
                invoiceRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId)
                        .stream().map(BillingService::toView).toList(),
                paymentRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId)
                        .stream().map(BillingService::toView).toList(),
                providerOptions(),
                gateway.manualInstructions());
    }

    @Transactional
    public BillingInvoiceRequestView requestManualInvoice(
            AuthenticatedUser principal, BillingInvoiceRequestCreate request) {
        BillingInvoiceRequest invoiceRequest = invoiceRequestRepository.saveAndFlush(
                BillingInvoiceRequest.request(principal.organizationId(), principal.userId(),
                        request.description(), clock.instant()));
        return toView(invoiceRequest);
    }

    public BillingPaymentView startPayment(AuthenticatedUser principal, UUID invoiceId,
            StartBillingPaymentRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "A non-empty Idempotency-Key of at most 128 characters is required.");
        }
        String provider = request.provider();
        BillingPayment previous = paymentRepository
                .findByOrganizationIdAndIdempotencyKey(principal.organizationId(), idempotencyKey.trim())
                .orElse(null);
        if (previous != null) {
            if (!previous.getInvoiceId().equals(invoiceId) || !previous.getProvider().equals(provider)) {
                throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different billing operation.");
            }
            return toView(previous);
        }
        if (!gateway.available(provider)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_UNAVAILABLE",
                    provider + " is not configured for this deployment.");
        }
        BillingInvoice invoice = invoiceRepository.findByIdAndOrganizationId(invoiceId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice"));
        if (!"OPEN".equals(invoice.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVOICE_NOT_PAYABLE",
                    "Only open invoices can be paid.");
        }
        boolean anotherPaymentPending = paymentRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId).stream()
                .anyMatch(payment -> "PENDING".equals(payment.getStatus())
                        || "AWAITING_MANUAL".equals(payment.getStatus()));
        if (anotherPaymentPending) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_ALREADY_PENDING",
                    "This invoice already has a payment awaiting confirmation.");
        }

        BillingPayment payment = BillingPayment.start(
                invoiceId, principal.organizationId(), provider, idempotencyKey.trim(),
                request.phoneNumber(), clock.instant());
        try {
            payment = paymentPersistence.create(payment);
        } catch (DataIntegrityViolationException conflict) {
            BillingPayment winner = paymentRepository
                    .findByOrganizationIdAndIdempotencyKey(principal.organizationId(), idempotencyKey.trim())
                    .orElse(null);
            if (winner != null) {
                if (!winner.getInvoiceId().equals(invoiceId) || !winner.getProvider().equals(provider)) {
                    throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                            "This Idempotency-Key was already used for a different billing operation.");
                }
                return toView(winner);
            }
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_ALREADY_PENDING",
                    "Another payment attempt is already active for this invoice.");
        }
        BillingGateway.GatewayResult result = gateway.start(provider, invoice, payment, request.phoneNumber());
        return toView(paymentPersistence.attachProviderResult(payment.getId(), result.reference(), result.checkoutUrl()));
    }

    @Transactional
    public BillingPaymentView refreshPayment(AuthenticatedUser principal, UUID paymentId) {
        BillingPayment payment = paymentRepository.findByIdAndOrganizationId(paymentId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment"));
        if ("SUCCEEDED".equals(payment.getStatus()) || "FAILED".equals(payment.getStatus())
                || "CANCELLED".equals(payment.getStatus()) || "AWAITING_MANUAL".equals(payment.getStatus())) {
            return toView(payment);
        }
        BillingInvoice invoice = invoiceRepository.findByIdAndOrganizationId(
                        payment.getInvoiceId(), principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice"));
        applyProviderState(payment, invoice, gateway.refresh(payment.getProvider(), payment, invoice));
        return toView(paymentRepository.saveAndFlush(payment));
    }

    public BillingPaymentView retryStripeCheckout(AuthenticatedUser principal, UUID paymentId) {
        BillingPayment payment = paymentRepository.findByIdAndOrganizationId(paymentId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment"));
        if (!"STRIPE".equals(payment.getProvider()) || !"PENDING".equals(payment.getStatus())
                || payment.getProviderReference() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_NOT_RETRYABLE",
                    "Only a pending Stripe checkout without a provider reference can be retried.");
        }
        BillingInvoice invoice = invoiceRepository.findByIdAndOrganizationId(
                        payment.getInvoiceId(), principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice"));
        BillingGateway.GatewayResult result = gateway.start("STRIPE", invoice, payment, null);
        return toView(paymentPersistence.attachProviderResult(
                payment.getId(), result.reference(), result.checkoutUrl()));
    }

    @Transactional
    public BillingPaymentView cancelManualPayment(AuthenticatedUser principal, UUID paymentId) {
        BillingPayment payment = paymentRepository.findByIdAndOrganizationId(paymentId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment"));
        try {
            payment.cancelManual(principal.userId(), "Cancelled by organization member.", clock.instant());
        } catch (IllegalStateException conflict) {
            throw new BusinessException(HttpStatus.CONFLICT, "MANUAL_PAYMENT_NOT_CANCELLABLE",
                    "Only an awaiting manual settlement can be cancelled.");
        }
        return toView(paymentRepository.saveAndFlush(payment));
    }

    @Transactional(readOnly = true)
    public List<BillingInvoiceRequestView> pendingInvoiceRequests() {
        return invoiceRequestRepository.findByStatusOrderByCreatedAtAsc("REQUESTED")
                .stream().map(BillingService::toView).toList();
    }

    @Transactional
    public BillingInvoiceView issueInvoice(
            com.pesaguard.backend.billing.api.BillingInvoiceRequest request,
            AuthenticatedOperator operator, String reason) {
        operator.require(OperatorCapability.BILLING_WRITE);
        reason = requireActionReason(reason);
        ensureOrganizationActive(request.organizationId());
        if (request.invoiceRequestId() != null) {
            BillingInvoiceRequest invoiceRequest = invoiceRequestRepository
                    .findByIdAndOrganizationId(request.invoiceRequestId(), request.organizationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Invoice request"));
            if (!"REQUESTED".equals(invoiceRequest.getStatus())) {
                throw new BusinessException(HttpStatus.CONFLICT, "INVOICE_REQUEST_RESOLVED",
                        "This invoice request has already been resolved.");
            }
            invoiceRequest.resolve("ISSUED", operator.operatorId(), reason, clock.instant());
            invoiceRequestRepository.saveAndFlush(invoiceRequest);
        }
        String invoiceNumber = "PG-" + Year.now(clock) + "-" + UUID.randomUUID().toString()
                .substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        BillingInvoice invoice = invoiceRepository.saveAndFlush(BillingInvoice.issue(
                request.organizationId(), request.invoiceRequestId(), invoiceNumber, request.description(),
                request.amountMinor(), request.currency(), request.dueAt(), operator.operatorId(),
                reason, clock.instant()));
        return toView(invoice);
    }

    @Transactional
    public void declineInvoiceRequest(UUID requestId, AuthenticatedOperator operator, String reason) {
        operator.require(OperatorCapability.BILLING_WRITE);
        BillingInvoiceRequest invoiceRequest = invoiceRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice request"));
        invoiceRequest.resolve("DECLINED", operator.operatorId(), requireActionReason(reason), clock.instant());
        invoiceRequestRepository.saveAndFlush(invoiceRequest);
    }

    @Transactional
    public void voidInvoice(UUID invoiceId, AuthenticatedOperator operator, String reason) {
        operator.require(OperatorCapability.BILLING_WRITE);
        BillingInvoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice"));
        boolean paymentPending = paymentRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId).stream()
                .anyMatch(payment -> "PENDING".equals(payment.getStatus())
                        || "AWAITING_MANUAL".equals(payment.getStatus()));
        if (paymentPending) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_REQUIRES_RECONCILIATION",
                    "Pending payments must be reconciled before this invoice can be voided.");
        }
        try {
            invoice.voidInvoice(operator.operatorId(), requireActionReason(reason), clock.instant());
        } catch (IllegalStateException conflict) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVOICE_ALREADY_PAID",
                    "A paid invoice cannot be voided.");
        }
        invoiceRepository.saveAndFlush(invoice);
    }

    @Transactional
    public BillingPaymentView confirmManualPayment(
            UUID paymentId, AuthenticatedOperator operator, String reason) {
        operator.require(OperatorCapability.BILLING_WRITE);
        reason = requireActionReason(reason);
        BillingPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment"));
        BillingInvoice invoice = invoiceRepository.findById(payment.getInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice"));
        try {
            payment.markManuallyPaid(clock.instant(), operator.operatorId(), reason);
            invoice.markPaid(clock.instant());
        } catch (IllegalStateException conflict) {
            throw new BusinessException(HttpStatus.CONFLICT, "MANUAL_PAYMENT_NOT_CONFIRMABLE",
                    "The invoice or manual payment is no longer awaiting confirmation.");
        }
        invoiceRepository.saveAndFlush(invoice);
        return toView(paymentRepository.saveAndFlush(payment));
    }

    @Transactional
    public BillingPaymentView reconcilePayment(
            UUID paymentId,
            BillingPaymentReconciliationRequest request,
            AuthenticatedOperator operator) {
        operator.require(OperatorCapability.BILLING_WRITE);
        String reason = requireActionReason(request.reason());
        BillingPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment"));
        BillingInvoice invoice = invoiceRepository.findById(payment.getInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice"));
        String finalStatus = "PAID".equals(request.status()) ? "SUCCEEDED" : "FAILED";
        try {
            payment.reconcile(finalStatus, operator.operatorId(), reason, clock.instant());
        } catch (IllegalStateException conflict) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_NOT_RECONCILABLE",
                    "Only a pending provider payment can be reconciled.");
        }
        if ("SUCCEEDED".equals(finalStatus) && "OPEN".equals(invoice.getStatus())) {
            invoice.markPaid(clock.instant());
            invoiceRepository.saveAndFlush(invoice);
        }
        return toView(paymentRepository.saveAndFlush(payment));
    }

    @Transactional
    public void acceptStripeWebhook(byte[] body, String signature) {
        if (!gateway.verifyStripeSignature(body, signature)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE",
                    "The payment-provider signature is invalid.");
        }
        JsonNode event = gateway.readJson(body);
        String eventId = text(event, "id");
        if (eventId == null || eventId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_EVENT",
                    "The payment-provider event is missing its event ID.");
        }
        int inserted = webhookEventRepository.registerOnce(
                "STRIPE", eventId, crypto.sha256(new String(body, StandardCharsets.UTF_8)));
        if (inserted == 0) return;
        if ("checkout.session.completed".equals(text(event, "type"))) {
            JsonNode session = event.path("data").path("object");
            if ("paid".equals(text(session, "payment_status"))) {
                String paymentIdValue = text(session, "metadata.payment_id");
                UUID paymentId;
                try {
                    paymentId = UUID.fromString(paymentIdValue);
                } catch (IllegalArgumentException | NullPointerException malformed) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_EVENT",
                            "The payment-provider event does not identify a known payment.");
                }
                BillingPayment payment = paymentRepository.findById(paymentId)
                        .orElseThrow(() -> new ResourceNotFoundException("Payment"));
                String sessionId = text(session, "id");
                if (!"STRIPE".equals(payment.getProvider()) || sessionId == null
                        || (payment.getProviderReference() != null
                                && !Objects.equals(payment.getProviderReference(), sessionId))) {
                    throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_REFERENCE_MISMATCH",
                            "The provider event does not match the pending payment.");
                }
                if (payment.getProviderReference() == null) payment.attachProviderResult(sessionId, null);
                BillingInvoice invoice = invoiceRepository.findById(payment.getInvoiceId())
                        .orElseThrow(() -> new ResourceNotFoundException("Invoice"));
                applyProviderState(payment, invoice, BillingGateway.PaymentState.PAID);
                paymentRepository.saveAndFlush(payment);
                invoiceRepository.saveAndFlush(invoice);
            }
        }
        webhookEventRepository.findById(new com.pesaguard.backend.billing.domain.BillingWebhookEventId(
                "STRIPE", eventId)).ifPresent(webhookEvent -> {
                    webhookEvent.markProcessed(clock.instant());
                    webhookEventRepository.save(webhookEvent);
                });
    }

    @Transactional
    public void acceptPayHeroCallback(byte[] body) {
        JsonNode callback = gateway.readJson(body);
        UUID paymentId;
        try {
            paymentId = UUID.fromString(firstText(callback, "ExternalReference", "external_reference"));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_EVENT",
                    "The PayHero callback does not identify a known payment.");
        }
        String reference = firstText(callback, "CheckoutRequestID", "checkout_request_id");
        BillingPayment payment = paymentRepository.findById(paymentId)
                .filter(candidate -> "PAYHERO".equals(candidate.getProvider()))
                .orElseThrow(() -> new ResourceNotFoundException("Payment"));
        if (reference == null || !Objects.equals(payment.getProviderReference(), reference)) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_REFERENCE_MISMATCH",
                    "The callback does not match the payment reference already recorded by the provider.");
        }
        BillingInvoice invoice = invoiceRepository.findById(payment.getInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice"));
        applyProviderState(payment, invoice, gateway.refresh("PAYHERO", payment, invoice));
        paymentRepository.saveAndFlush(payment);
        invoiceRepository.saveAndFlush(invoice);
    }

    @Transactional
    public void acceptDarajaCallback(byte[] body) {
        JsonNode callback = gateway.readJson(body);
        String reference = firstText(callback, "Body.stkCallback.CheckoutRequestID",
                "checkoutRequestID", "CheckoutRequestID");
        if (reference == null || reference.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_EVENT",
                    "The Daraja callback does not include a checkout request reference.");
        }
        BillingPayment payment = paymentRepository.findByProviderAndProviderReference("DARAJA", reference)
                .orElseThrow(() -> new ResourceNotFoundException("Payment"));
        BillingInvoice invoice = invoiceRepository.findById(payment.getInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice"));
        applyProviderState(payment, invoice, gateway.refresh("DARAJA", payment, invoice));
        paymentRepository.saveAndFlush(payment);
        invoiceRepository.saveAndFlush(invoice);
    }

    @Transactional
    public void acceptAirtelCallback(byte[] body) {
        JsonNode callback = gateway.readJson(body);
        String reference = firstText(callback, "transaction.id", "transactionId", "transaction_id", "id");
        if (reference == null || reference.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_EVENT",
                    "The Airtel Money callback does not include a transaction reference.");
        }
        BillingPayment payment = paymentRepository.findByProviderAndProviderReference("AIRTEL_MONEY", reference)
                .orElseThrow(() -> new ResourceNotFoundException("Payment"));
        BillingInvoice invoice = invoiceRepository.findById(payment.getInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Invoice"));
        applyProviderState(payment, invoice, gateway.refresh("AIRTEL_MONEY", payment, invoice));
        paymentRepository.saveAndFlush(payment);
        invoiceRepository.saveAndFlush(invoice);
    }

    private List<BillingProviderOption> providerOptions() {
        return List.of(
                new BillingProviderOption("STRIPE", "Card or supported Stripe method", gateway.available("STRIPE"), false),
                new BillingProviderOption("PAYHERO", "PayHero · M-Pesa", gateway.available("PAYHERO"), true),
                new BillingProviderOption("DARAJA", "Safaricom M-Pesa Daraja", gateway.available("DARAJA"), true),
                new BillingProviderOption("AIRTEL_MONEY", "Airtel Money", gateway.available("AIRTEL_MONEY"), true),
                new BillingProviderOption("MANUAL", "Manual invoice settlement", gateway.available("MANUAL"), false));
    }

    private void ensureOrganizationActive(UUID organizationId) {
        organizationRepository.findById(organizationId)
                .filter(organization -> organization.getStatus() == OrganizationStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Active organization"));
    }

    private static String requireActionReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_OPERATOR_REASON",
                    "A reason of 1 to 500 characters is required for this billing action.");
        }
        StringBuilder cleaned = new StringBuilder(reason.length());
        for (int index = 0; index < reason.length(); index++) {
            char character = reason.charAt(index);
            if (character >= 0x20 && character != 0x7F) cleaned.append(character);
        }
        String normalized = cleaned.toString().trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_OPERATOR_REASON",
                    "A reason of 1 to 500 characters is required for this billing action.");
        }
        return normalized;
    }

    private void applyProviderState(
            BillingPayment payment, BillingInvoice invoice, BillingGateway.PaymentState providerState) {
        if (providerState == BillingGateway.PaymentState.PAID) {
            payment.markPaid(clock.instant());
            if ("SUCCEEDED".equals(payment.getStatus()) && "OPEN".equals(invoice.getStatus())) {
                invoice.markPaid(clock.instant());
            }
        } else if (providerState == BillingGateway.PaymentState.FAILED) {
            payment.markFailed();
        }
    }

    private static BillingInvoiceView toView(BillingInvoice invoice) {
        return new BillingInvoiceView(invoice.getId(), invoice.getInvoiceNumber(), invoice.getDescription(),
                invoice.getAmountMinor(), invoice.getCurrency(), invoice.getStatus(), invoice.getDueAt(),
                invoice.getIssuedAt(), invoice.getPaidAt());
    }

    private static BillingInvoiceRequestView toView(BillingInvoiceRequest request) {
        return new BillingInvoiceRequestView(request.getId(), request.getDescription(), request.getStatus(),
                request.getCreatedAt(), request.getReviewedAt());
    }

    private static BillingPaymentView toView(BillingPayment payment) {
        return new BillingPaymentView(payment.getId(), payment.getInvoiceId(), payment.getProvider(),
                payment.getStatus(), payment.getProviderReference(), payment.getCheckoutUrl(),
                payment.getPhoneLastFour(), payment.getCreatedAt(), payment.getPaidAt());
    }

    private static String firstText(JsonNode node, String... paths) {
        if (node == null) return null;
        for (String path : paths) {
            String value = text(node, path);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String text(JsonNode node, String path) {
        JsonNode current = node;
        for (String part : path.split("\\.")) current = current.path(part);
        return current.isMissingNode() || current.isNull() ? null : current.asText(null);
    }
}
