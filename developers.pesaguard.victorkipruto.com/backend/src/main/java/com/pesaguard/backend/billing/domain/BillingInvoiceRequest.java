package com.pesaguard.backend.billing.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "billing_invoice_requests")
public class BillingInvoiceRequest {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "review_reason", length = 500)
    private String reviewReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BillingInvoiceRequest() {
    }

    private BillingInvoiceRequest(UUID organizationId, UUID requestedBy, String description, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.requestedBy = requestedBy;
        this.description = description.trim();
        this.status = "REQUESTED";
        this.createdAt = now;
    }

    public static BillingInvoiceRequest request(
            UUID organizationId, UUID requestedBy, String description, Instant now) {
        return new BillingInvoiceRequest(organizationId, requestedBy, description, now);
    }

    public void resolve(String status, UUID operatorId, String reason, Instant now) {
        if (!"REQUESTED".equals(this.status)) {
            throw new IllegalStateException("Only a pending invoice request can be resolved.");
        }
        if (!"ISSUED".equals(status) && !"DECLINED".equals(status)) {
            throw new IllegalArgumentException("Unsupported invoice-request status.");
        }
        this.status = status;
        this.reviewedBy = operatorId;
        this.reviewReason = reason;
        this.reviewedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewReason() { return reviewReason; }
    public Instant getCreatedAt() { return createdAt; }
}
