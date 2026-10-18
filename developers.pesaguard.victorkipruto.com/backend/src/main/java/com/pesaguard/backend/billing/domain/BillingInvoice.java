package com.pesaguard.backend.billing.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "billing_invoices")
public class BillingInvoice {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "invoice_request_id", unique = true)
    private UUID invoiceRequestId;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 48)
    private String invoiceNumber;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "issued_by", nullable = false)
    private UUID issuedBy;

    @Column(name = "issued_reason", nullable = false, length = 500)
    private String issuedReason;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "voided_by")
    private UUID voidedBy;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected BillingInvoice() {
    }

    private BillingInvoice(UUID organizationId, UUID invoiceRequestId, String invoiceNumber,
            String description, long amountMinor, String currency, Instant dueAt, UUID issuedBy,
            String issuedReason, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.invoiceRequestId = invoiceRequestId;
        this.invoiceNumber = invoiceNumber;
        this.description = description.trim();
        this.amountMinor = amountMinor;
        this.currency = currency.trim().toUpperCase(java.util.Locale.ROOT);
        this.status = "OPEN";
        this.dueAt = dueAt;
        this.issuedBy = issuedBy;
        this.issuedReason = issuedReason;
        this.issuedAt = now;
    }

    public static BillingInvoice issue(UUID organizationId, UUID invoiceRequestId, String invoiceNumber,
            String description, long amountMinor, String currency, Instant dueAt, UUID issuedBy,
            String issuedReason, Instant now) {
        if (amountMinor < 1 || currency == null || !currency.matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException("An invoice requires a positive amount and three-letter currency.");
        }
        return new BillingInvoice(organizationId, invoiceRequestId, invoiceNumber,
                description, amountMinor, currency, dueAt, issuedBy, issuedReason, now);
    }

    public void markPaid(Instant now) {
        if ("PAID".equals(status)) return;
        if (!"OPEN".equals(status)) {
            throw new IllegalStateException("Only an open invoice can be paid.");
        }
        status = "PAID";
        paidAt = now;
    }

    public void voidInvoice(UUID operatorId, String reason, Instant now) {
        if (!"OPEN".equals(status)) {
            throw new IllegalStateException("Only an open invoice can be voided.");
        }
        status = "VOID";
        voidedBy = operatorId;
        voidReason = reason;
        voidedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getInvoiceRequestId() { return invoiceRequestId; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public String getDescription() { return description; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public Instant getDueAt() { return dueAt; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getPaidAt() { return paidAt; }
    public String getIssuedReason() { return issuedReason; }
    public UUID getVoidedBy() { return voidedBy; }
    public String getVoidReason() { return voidReason; }
    public Instant getVoidedAt() { return voidedAt; }
}
