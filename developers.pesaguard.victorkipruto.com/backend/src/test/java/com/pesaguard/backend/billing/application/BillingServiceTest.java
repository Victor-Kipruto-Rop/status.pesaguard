package com.pesaguard.backend.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.pesaguard.backend.billing.api.StartBillingPaymentRequest;
import com.pesaguard.backend.billing.api.BillingPaymentReconciliationRequest;
import com.pesaguard.backend.billing.domain.BillingInvoice;
import com.pesaguard.backend.billing.domain.BillingPayment;
import com.pesaguard.backend.billing.infrastructure.BillingInvoiceRepository;
import com.pesaguard.backend.billing.infrastructure.BillingInvoiceRequestRepository;
import com.pesaguard.backend.billing.infrastructure.BillingPaymentRepository;
import com.pesaguard.backend.billing.infrastructure.BillingWebhookEventRepository;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.organization.infrastructure.OrganizationRepository;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;
import com.pesaguard.backend.security.credentials.CredentialCryptoService;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private final UUID organizationId = UUID.randomUUID();
    private final AuthenticatedUser principal = new AuthenticatedUser(
            UUID.randomUUID(), organizationId, UUID.randomUUID(), "developer@example.com", "Developer", Set.of());
    private final AuthenticatedOperator operator = new AuthenticatedOperator(
            UUID.randomUUID(), "billing-operator", Set.of(OperatorCapability.BILLING_WRITE), null);

    @Mock private BillingInvoiceRepository invoiceRepository;
    @Mock private BillingInvoiceRequestRepository invoiceRequestRepository;
    @Mock private BillingPaymentRepository paymentRepository;
    @Mock private BillingPaymentPersistence paymentPersistence;
    @Mock private BillingWebhookEventRepository webhookEventRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private BillingGateway gateway;
    @Mock private CredentialCryptoService crypto;

    private BillingService service;

    @BeforeEach
    void setUp() {
        service = new BillingService(invoiceRepository, invoiceRequestRepository, paymentRepository, paymentPersistence,
                webhookEventRepository, organizationRepository, gateway,
                crypto,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void refusesPaymentsWhenProviderIsNotConfigured() {
        when(gateway.available("STRIPE")).thenReturn(false);

        assertThatThrownBy(() -> service.startPayment(principal, UUID.randomUUID(),
                new StartBillingPaymentRequest("STRIPE", null), "request-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).status())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(invoiceRepository, never()).findByIdAndOrganizationId(any(), any());
    }

    @Test
    void returnsPreviousPaymentForSameIdempotencyKeyWithoutStartingAnotherCharge() {
        UUID invoiceId = UUID.randomUUID();
        BillingPayment existing = BillingPayment.start(
                invoiceId, organizationId, "STRIPE", "same-key", null, NOW);
        when(paymentRepository.findByOrganizationIdAndIdempotencyKey(organizationId, "same-key"))
                .thenReturn(Optional.of(existing));

        var result = service.startPayment(principal, invoiceId,
                new StartBillingPaymentRequest("STRIPE", null), "same-key");

        assertThat(result.id()).isEqualTo(existing.getId());
        assertThat(result.status()).isEqualTo("PENDING");
        verify(gateway, never()).start(any(), any(), any(), any());
    }

    @Test
    void rejectsReusingAnIdempotencyKeyForAnotherInvoice() {
        BillingPayment existing = BillingPayment.start(
                UUID.randomUUID(), organizationId, "STRIPE", "same-key", null, NOW);
        when(paymentRepository.findByOrganizationIdAndIdempotencyKey(organizationId, "same-key"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.startPayment(principal, UUID.randomUUID(),
                new StartBillingPaymentRequest("STRIPE", null), "same-key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("different billing operation");
    }

    @Test
    void operatorManualConfirmationMarksPaymentAndInvoicePaidWithAuditReason() {
        BillingInvoice invoice = BillingInvoice.issue(organizationId, null, "PG-2026-TEST0001",
                "API service", 2500, "KES", null, operator.operatorId(), "Approved invoice", NOW);
        BillingPayment payment = BillingPayment.start(
                invoice.getId(), organizationId, "MANUAL", "manual-key", null, NOW);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentRepository.saveAndFlush(any(BillingPayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepository.saveAndFlush(any(BillingInvoice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.confirmManualPayment(payment.getId(), operator, "  bank transfer verified  ");

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(payment.getManualConfirmationReason()).isEqualTo("bank transfer verified");
        assertThat(payment.getManuallyConfirmedBy()).isEqualTo(operator.operatorId());
        assertThat(invoice.getStatus()).isEqualTo("PAID");
        assertThat(invoice.getPaidAt()).isEqualTo(NOW);
    }

    @Test
    void blocksVoidingAnInvoiceWithPaymentAwaitingReconciliation() {
        BillingInvoice invoice = BillingInvoice.issue(organizationId, null, "PG-2026-TEST0002",
                "API service", 2500, "KES", null, operator.operatorId(), "Approved invoice", NOW);
        BillingPayment payment = BillingPayment.start(
                invoice.getId(), organizationId, "MANUAL", "manual-key", null, NOW);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentRepository.findByInvoiceIdOrderByCreatedAtDesc(invoice.getId())).thenReturn(java.util.List.of(payment));

        assertThatThrownBy(() -> service.voidInvoice(invoice.getId(), operator, "close invoice"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reconciled");
        verify(invoiceRepository, never()).saveAndFlush(any(BillingInvoice.class));
    }

    @Test
    void developerCanCancelAnAwaitingManualSettlement() {
        BillingPayment payment = BillingPayment.start(
                UUID.randomUUID(), organizationId, "MANUAL", "manual-key", null, NOW);
        when(paymentRepository.findByIdAndOrganizationId(payment.getId(), organizationId))
                .thenReturn(Optional.of(payment));
        when(paymentRepository.saveAndFlush(any(BillingPayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.cancelManualPayment(principal, payment.getId());

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(payment.getCancelledBy()).isEqualTo(principal.userId());
        assertThat(payment.getCancellationReason()).isNotBlank();
        assertThat(payment.getCancelledAt()).isEqualTo(NOW);
    }

    @Test
    void operatorReconciliationRecordsReasonAndUpdatesOpenInvoice() {
        BillingInvoice invoice = BillingInvoice.issue(organizationId, null, "PG-2026-TEST0003",
                "API service", 2500, "KES", null, operator.operatorId(), "Approved invoice", NOW);
        BillingPayment payment = BillingPayment.start(
                invoice.getId(), organizationId, "STRIPE", "stripe-key", null, NOW);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentRepository.saveAndFlush(any(BillingPayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepository.saveAndFlush(any(BillingInvoice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.reconcilePayment(payment.getId(),
                new BillingPaymentReconciliationRequest("PAID", "Verified settled in provider dashboard"), operator);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(payment.getReconciledBy()).isEqualTo(operator.operatorId());
        assertThat(payment.getReconciliationReason()).isEqualTo("Verified settled in provider dashboard");
        assertThat(invoice.getStatus()).isEqualTo("PAID");
    }
}
