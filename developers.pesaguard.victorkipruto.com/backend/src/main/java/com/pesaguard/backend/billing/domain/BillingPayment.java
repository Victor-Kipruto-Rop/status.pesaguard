package com.pesaguard.backend.billing.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "billing_payments")
public class BillingPayment {

    @Id
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 24)
    private String provider;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "checkout_url", columnDefinition = "text")
    private String checkoutUrl;

    @Column(name = "phone_last_four", length = 4)
    private String phoneLastFour;

    @Column(name = "manual_confirmation_reason", length = 500)
    private String manualConfirmationReason;

    @Column(name = "manually_confirmed_by")
    private UUID manuallyConfirmedBy;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "reconciled_by")
    private UUID reconciledBy;

    @Column(name = "reconciliation_reason", length = 500)
    private String reconciliationReason;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected BillingPayment() {
    }

    private BillingPayment(UUID invoiceId, UUID organizationId, String provider,
            String idempotencyKey, String phone, Instant now) {
        this.id = UUID.randomUUID();
        this.invoiceId = invoiceId;
        this.organizationId = organizationId;
        this.provider = provider;
        this.idempotencyKey = idempotencyKey;
        this.status = "MANUAL".equals(provider) ? "AWAITING_MANUAL" : "PENDING";
        this.phoneLastFour = lastFour(phone);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static BillingPayment start(UUID invoiceId, UUID organizationId, String provider,
            String idempotencyKey, String phone, Instant now) {
        return new BillingPayment(invoiceId, organizationId, provider, idempotencyKey, phone, now);
    }

    public void attachProviderResult(String reference, String checkoutUrl) {
        this.providerReference = reference;
        this.checkoutUrl = checkoutUrl;
    }

    public void markPaid(Instant now) {
        if ("SUCCEEDED".equals(status)) return;
        if ("FAILED".equals(status) || "CANCELLED".equals(status)) return;
        status = "SUCCEEDED";
        paidAt = now;
    }

    public void markManuallyPaid(Instant now, UUID operatorId, String reason) {
        if (!"MANUAL".equals(provider) || !"AWAITING_MANUAL".equals(status)) {
            throw new IllegalStateException("Only an awaiting manual payment can be manually confirmed.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A manual payment confirmation requires a reason.");
        }
        markPaid(now);
        manuallyConfirmedBy = operatorId;
        manualConfirmationReason = reason.trim();
    }

    public void markFailed() {
        if ("PENDING".equals(status)) status = "FAILED";
    }

    public void cancelManual(UUID userId, String reason, Instant now) {
        if (!"MANUAL".equals(provider) || !"AWAITING_MANUAL".equals(status)) {
            throw new IllegalStateException("Only an awaiting manual payment can be cancelled by the developer.");
        }
        status = "CANCELLED";
        cancelledBy = userId;
        cancellationReason = reason;
        cancelledAt = now;
    }

    public void reconcile(String finalStatus, UUID operatorId, String reason, Instant now) {
        if ("MANUAL".equals(provider) || !"PENDING".equals(status)
                || (!"SUCCEEDED".equals(finalStatus) && !"FAILED".equals(finalStatus))) {
            throw new IllegalStateException("Only a pending provider payment can be reconciled to a final state.");
        }
        status = finalStatus;
        reconciledBy = operatorId;
        reconciliationReason = reason;
        reconciledAt = now;
        if ("SUCCEEDED".equals(finalStatus)) paidAt = now;
    }

    public void markCancelled() {
        if ("PENDING".equals(status)) status = "CANCELLED";
    }

    private static String lastFour(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        return digits.length() < 4 ? null : digits.substring(digits.length() - 4);
    }

    public UUID getId() { return id; }
    public UUID getInvoiceId() { return invoiceId; }
    public UUID getOrganizationId() { return organizationId; }
    public String getProvider() { return provider; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getStatus() { return status; }
    public String getProviderReference() { return providerReference; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public String getPhoneLastFour() { return phoneLastFour; }
    public String getManualConfirmationReason() { return manualConfirmationReason; }
    public UUID getManuallyConfirmedBy() { return manuallyConfirmedBy; }
    public UUID getCancelledBy() { return cancelledBy; }
    public String getCancellationReason() { return cancellationReason; }
    public Instant getCancelledAt() { return cancelledAt; }
    public UUID getReconciledBy() { return reconciledBy; }
    public String getReconciliationReason() { return reconciliationReason; }
    public Instant getReconciledAt() { return reconciledAt; }
    public Instant getPaidAt() { return paidAt; }
    public Instant getCreatedAt() { return createdAt; }
}
