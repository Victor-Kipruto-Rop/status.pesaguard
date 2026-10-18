package com.pesaguard.backend.rbac.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A request for time-boxed production access.
 *
 * <p>Separation of duties is enforced in two places: the domain refuses to
 * approve a request raised by the reviewer, and the database refuses to persist
 * a row where {@code reviewed_by = requested_by}.
 */
@Entity
@Table(name = "production_access_requests")
public class ProductionAccessRequest {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "application_name", nullable = false, length = 160)
    private String applicationName;

    @Column(name = "organization_details", nullable = false, length = 2000)
    private String organizationDetails;

    @Column(name = "intended_api_usage", nullable = false, length = 2000)
    private String intendedApiUsage;

    @Column(name = "requested_scopes", nullable = false, length = 1000)
    private String requestedScopes;

    @Column(name = "requested_limits", nullable = false, length = 1000)
    private String requestedLimits;

    @Column(name = "integration_information", nullable = false, length = 2000)
    private String integrationInformation;

    @Column(name = "security_information", nullable = false, length = 2000)
    private String securityInformation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ProductionAccessStatus status;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;



    // Activation is recorded separately from the review decision so it is always
    // answerable who brought a grant live and when, distinct from who approved it.
    @Column(name = "activated_by")
    private UUID activatedBy;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "suspension_reason", length = 1000)
    private String suspensionReason;

    @Column(name = "revocation_reason", length = 1000)
    private String revocationReason;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductionAccessRequest() {
    }

    private ProductionAccessRequest(UUID organizationId, UUID projectId, UUID environmentId,
            UUID requestedBy, String reason, String applicationName, String organizationDetails,
            String intendedApiUsage, String requestedScopes, String requestedLimits,
            String integrationInformation, String securityInformation, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.requestedBy = requestedBy;
        this.reason = reason.trim();
        this.applicationName = applicationName.trim();
        this.organizationDetails = organizationDetails.trim();
        this.intendedApiUsage = intendedApiUsage.trim();
        this.requestedScopes = requestedScopes.trim();
        this.requestedLimits = requestedLimits.trim();
        this.integrationInformation = integrationInformation.trim();
        this.securityInformation = securityInformation.trim();
        this.status = ProductionAccessStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ProductionAccessRequest create(UUID organizationId, UUID projectId, UUID environmentId,
            UUID requestedBy, String reason, String applicationName, String organizationDetails,
            String intendedApiUsage, String requestedScopes, String requestedLimits,
            String integrationInformation, String securityInformation, Instant now) {
        return new ProductionAccessRequest(organizationId, projectId, environmentId, requestedBy,
                requireText(reason, "A reason is required for production access"),
                requireText(applicationName, "An application name is required"),
                requireText(organizationDetails, "Organization details are required"),
                requireText(intendedApiUsage, "Intended API usage is required"),
                requireText(requestedScopes, "Requested scopes are required"),
                requireText(requestedLimits, "Requested limits are required"),
                requireText(integrationInformation, "Integration information is required"),
                requireText(securityInformation, "Security information is required"), now);
    }

    public void approve(UUID reviewerId, String note, Instant expiresAt, Instant now) {
        requireReviewable();
        requireDifferentActor(reviewerId);
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new IllegalStateException("Approved access must have a future expiry");
        }
        // APPROVED records the decision only. It grants nothing: activation is a
        // separate, explicit step once provisioning has actually succeeded.
        this.status = ProductionAccessStatus.APPROVED;
        this.reviewedBy = reviewerId;
        this.reviewedAt = now;
        this.reviewNote = note == null || note.isBlank() ? null : note.trim();
        this.expiresAt = expiresAt;
        this.updatedAt = now;
    }

    /**
     * Marks a request as being evaluated.
     *
     * <p>Separation of duties applies to claiming as much as to deciding: the
     * requester cannot claim their own request for review.
     */
    public void beginReview(UUID reviewerId, Instant now) {
        requireReviewable();
        requireDifferentActor(reviewerId);
        this.status = ProductionAccessStatus.UNDER_REVIEW;
        this.reviewedBy = reviewerId;
        this.reviewedAt = now;
        this.updatedAt = now;
    }

    /**
     * Brings an approved grant live, after provisioning.
     *
     * <p>The expiry is re-checked here rather than trusted from approval time: a
     * request approved for 24 hours and activated four days later would otherwise
     * come up already expired, which is a confusing way to learn that the window
     * had passed.
     */
    public void activate(UUID actorId, Instant now) {
        if (status != ProductionAccessStatus.APPROVED) {
            throw new IllegalStateException("Only an approved request can be activated");
        }
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            // Recorded rather than silently ignored: an expired window should be
            // visible, and the request must be re-approved for a fresh period.
            this.status = ProductionAccessStatus.EXPIRED;
            this.updatedAt = now;
            throw new IllegalStateException(
                    "The approval window has elapsed; the request must be reviewed again");
        }
        this.status = ProductionAccessStatus.ACTIVE;
        this.activatedBy = actorId;
        this.activatedAt = now;
        this.updatedAt = now;
    }

    /**
     * Temporarily withdraws a live grant.
     *
     * <p>Reversible via {@link #reactivate}. Used for an incident where stopping
     * traffic is urgent but the integration is not being abandoned.
     */
    public void suspend(UUID actorId, String note, Instant now) {
        if (status != ProductionAccessStatus.ACTIVE) {
            throw new IllegalStateException("Only an active grant can be suspended");
        }
        this.status = ProductionAccessStatus.SUSPENDED;
        this.suspensionReason = requireText(note, "A suspension reason is required");
        this.updatedAt = now;
    }

    /**
     * Returns a suspended grant to service.
     *
     * <p>Still bounded by the original expiry: resumption does not extend the
     * window, or suspension would become a way to grant extra time indefinitely.
     */
    public void reactivate(UUID actorId, Instant now) {
        if (status != ProductionAccessStatus.SUSPENDED) {
            throw new IllegalStateException("Only a suspended grant can be reactivated");
        }
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            this.status = ProductionAccessStatus.EXPIRED;
            this.updatedAt = now;
            throw new IllegalStateException("The grant expired while it was suspended");
        }
        this.status = ProductionAccessStatus.ACTIVE;
        this.suspensionReason = null;
        this.updatedAt = now;
    }


    public void reject(UUID reviewerId, String note, Instant now) {
        requireReviewable();
        requireDifferentActor(reviewerId);
        this.status = ProductionAccessStatus.REJECTED;
        this.reviewedBy = reviewerId;
        this.reviewedAt = now;
        this.reviewNote = note == null || note.isBlank() ? null : note.trim();
        this.updatedAt = now;
    }

    public void cancel(UUID actorId, Instant now) {
        requireReviewable();
        if (!requestedBy.equals(actorId)) {
            throw new IllegalStateException("Only the requester can cancel a production access request");
        }
        this.status = ProductionAccessStatus.CANCELLED;
        this.updatedAt = now;
    }

    /**
     * Permanently withdraws a grant.
     *
     * <p>Terminal by design. There is no path back from {@code REVOKED}: a
     * revoked grant must be re-requested and re-reviewed, so a revocation can
     * never be quietly undone by whoever performs it.
     */
    public void revoke(UUID actorId, String note, Instant now) {
        if (status == ProductionAccessStatus.REVOKED) {
            throw new IllegalStateException("This grant is already revoked");
        }
        if (status.isTerminal()) {
            throw new IllegalStateException("A " + status + " request cannot be revoked");
        }
        this.status = ProductionAccessStatus.REVOKED;
        this.revocationReason = requireText(note, "A revocation reason is required");
        this.revokedBy = actorId;
        this.revokedAt = now;
        this.updatedAt = now;
    }


    /**
     * Moves an elapsed grant to EXPIRED.
     *
     * <p>Applies to ACTIVE and SUSPENDED alike. Only APPROVED is excluded, because an
     * approved-but-not-activated request holds no traffic to expire.
     */
    public void expireIfElapsed(Instant now) {
        boolean wasLive = status == ProductionAccessStatus.ACTIVE
                || status == ProductionAccessStatus.SUSPENDED;
        if (wasLive && expiresAt != null && !expiresAt.isAfter(now)) {
            this.status = ProductionAccessStatus.EXPIRED;
            this.updatedAt = now;
        }
    }

    public boolean isPending() {
        return status == ProductionAccessStatus.PENDING;
    }

    /**
     * Whether this request currently authorises production traffic. Only ACTIVE
     * qualifies: APPROVED has been agreed to but not yet provisioned.
     */
    public boolean isActiveGrant(Instant now) {
        expireIfElapsed(now);
        return status == ProductionAccessStatus.ACTIVE
                && expiresAt != null && expiresAt.isAfter(now);
    }

    private void requireReviewable() {
        if (status != ProductionAccessStatus.PENDING
                && status != ProductionAccessStatus.UNDER_REVIEW) {
            throw new IllegalStateException(
                    "Production access request is not open for review (status " + status + ")");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private void requireDifferentActor(UUID reviewerId) {
        if (requestedBy.equals(reviewerId)) {
            throw new IllegalStateException("Production access cannot be self-reviewed");
        }
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getReason() { return reason; }
    public String getApplicationName() { return applicationName; }
    public String getOrganizationDetails() { return organizationDetails; }
    public String getIntendedApiUsage() { return intendedApiUsage; }
    public String getRequestedScopes() { return requestedScopes; }
    public String getRequestedLimits() { return requestedLimits; }
    public String getIntegrationInformation() { return integrationInformation; }
    public String getSecurityInformation() { return securityInformation; }
    public ProductionAccessStatus getStatus() { return status; }
    public UUID getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewNote() { return reviewNote; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getActivatedBy() { return activatedBy; }
    public Instant getActivatedAt() { return activatedAt; }
    public String getSuspensionReason() { return suspensionReason; }
    public String getRevocationReason() { return revocationReason; }
    public UUID getRevokedBy() { return revokedBy; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
