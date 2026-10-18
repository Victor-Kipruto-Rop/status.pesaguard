package com.pesaguard.backend.support.domain;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.support.api.CreateSupportTicketRequest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_tickets")
public class SupportTicket {

    @Id
    private UUID id;

    @Column(name = "public_id", nullable = false, unique = true, length = 24, updatable = false)
    private String publicId;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "contact_email", nullable = false, length = 320, updatable = false)
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32, updatable = false)
    private SupportTicketCategory category;

    @Column(name = "subject", nullable = false, length = 180, updatable = false)
    private String subject;

    @Column(name = "description", nullable = false, length = 8000, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16, updatable = false)
    private SupportTicketPriority priority;

    @Column(name = "environment", length = 16, updatable = false)
    private String environment;

    @Column(name = "endpoint", length = 200, updatable = false)
    private String endpoint;

    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Column(name = "request_id", length = 100, updatable = false)
    private String requestId;

    @Column(name = "delivery_id", length = 100, updatable = false)
    private String deliveryId;

    @Column(name = "event_type", length = 100, updatable = false)
    private String eventType;

    @Column(name = "authentication_method", length = 60, updatable = false)
    private String authenticationMethod;

    @Column(name = "error_code", length = 80, updatable = false)
    private String errorCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SupportTicketStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by_operator")
    private UUID resolvedByOperator;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "operator_action_reason", length = 500)
    private String operatorActionReason;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected SupportTicket() {
    }

    private SupportTicket(UUID organizationId, UUID userId, String contactEmail,
            SupportTicketCategory category, String subject, String description,
            SupportTicketPriority priority, CreateSupportTicketRequest request, Instant now) {
        this.id = UUID.randomUUID();
        this.publicId = "SUP-" + this.id.toString().replace("-", "").substring(0, 16).toUpperCase();
        this.organizationId = organizationId;
        this.userId = userId;
        this.contactEmail = contactEmail;
        this.category = category;
        this.subject = subject;
        this.description = description;
        this.priority = priority;
        this.environment = blankToNull(request.environment());
        this.endpoint = blankToNull(request.endpoint());
        this.httpStatus = request.httpStatus();
        this.requestId = blankToNull(request.requestId());
        this.deliveryId = blankToNull(request.deliveryId());
        this.eventType = blankToNull(request.eventType());
        this.authenticationMethod = blankToNull(request.authenticationMethod());
        this.errorCode = blankToNull(request.errorCode());
        this.status = SupportTicketStatus.OPEN;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static SupportTicket open(UUID organizationId, UUID userId, String contactEmail,
            SupportTicketCategory category, String subject, String description,
            SupportTicketPriority priority, CreateSupportTicketRequest request, Instant now) {
        return new SupportTicket(organizationId, userId, contactEmail, category,
                subject.trim(), description.trim(), priority, request, now);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public void close(Instant now) {
        this.status = SupportTicketStatus.CLOSED;
        this.closedAt = now;
        this.updatedAt = now;
    }

    public void resolve(UUID operatorId, String note, String reason, Instant now) {
        if (status == SupportTicketStatus.RESOLVED || status == SupportTicketStatus.CLOSED) {
            throw new IllegalStateException("Only an active support ticket can be resolved.");
        }
        this.status = SupportTicketStatus.RESOLVED;
        this.resolvedAt = now;
        this.resolvedByOperator = operatorId;
        this.resolutionNote = note.trim();
        this.operatorActionReason = reason.trim();
        this.closedAt = null;
        this.updatedAt = now;
    }

    public void reopen(Instant now) {
        this.status = SupportTicketStatus.OPEN;
        this.resolvedAt = null;
        this.resolvedByOperator = null;
        this.resolutionNote = null;
        this.operatorActionReason = null;
        this.closedAt = null;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getPublicId() { return publicId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public String getContactEmail() { return contactEmail; }
    public SupportTicketCategory getCategory() { return category; }
    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public SupportTicketPriority getPriority() { return priority; }
    public String getEnvironment() { return environment; }
    public String getEndpoint() { return endpoint; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getRequestId() { return requestId; }
    public String getDeliveryId() { return deliveryId; }
    public String getEventType() { return eventType; }
    public String getAuthenticationMethod() { return authenticationMethod; }
    public String getErrorCode() { return errorCode; }
    public SupportTicketStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public UUID getResolvedByOperator() { return resolvedByOperator; }
    public String getResolutionNote() { return resolutionNote; }
    public String getOperatorActionReason() { return operatorActionReason; }
    public Instant getClosedAt() { return closedAt; }
}
