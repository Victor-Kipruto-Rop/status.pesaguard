package com.pesaguard.backend.rbac.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.environment.domain.EnvironmentType;
import com.pesaguard.backend.environment.infrastructure.ProjectEnvironmentRepository;
import com.pesaguard.backend.rbac.api.ProductionAccessRequestView;
import com.pesaguard.backend.rbac.api.ProductionAccessReviewRequest;
import com.pesaguard.backend.rbac.api.RequestProductionAccessRequest;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.rbac.api.ProductionAccessHistoryView;
import com.pesaguard.backend.rbac.domain.ProductionAccessHistoryEntry;
import com.pesaguard.backend.rbac.domain.ProductionAccessRequest;
import com.pesaguard.backend.rbac.domain.ProductionAccessStatus;
import com.pesaguard.backend.rbac.infrastructure.ProductionAccessHistoryRepository;
import com.pesaguard.backend.rbac.infrastructure.ProductionAccessRequestRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.notifications.application.ProductionAccessNotificationEmitter;
import com.pesaguard.backend.events.application.DeveloperEventEmitter;

/**
 * Production access is request/review, never self-service. Requesting requires
 * {@code production_access:request}, reviewing requires
 * {@code production_access:review}, and the reviewer may not be the requester.
 * Grants are always time-boxed.
 */
@Service
public class ProductionAccessService {

    private static final Duration MAX_GRANT = Duration.ofDays(7);

    private final ProductionAccessRequestRepository repository;
    private final ProductionAccessHistoryRepository historyRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;
    private final ProductionAccessNotificationEmitter notifications;
    private final DeveloperEventEmitter eventEmitter;

    public ProductionAccessService(
            ProductionAccessRequestRepository repository,
            ProductionAccessHistoryRepository historyRepository,
            ProjectEnvironmentRepository environmentRepository,
            AuthorizationService authorizationService,
            AuditService auditService,
            Clock clock,
            ProductionAccessNotificationEmitter notifications,
            DeveloperEventEmitter eventEmitter) {
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.environmentRepository = environmentRepository;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.clock = clock;
        this.notifications = notifications;
        this.eventEmitter = eventEmitter;
    }

    @Transactional
    public ProductionAccessRequestView request(AuthenticatedUser principal,
            UUID projectId, RequestProductionAccessRequest request) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_ACCESS_REQUEST);
        var environment = environmentRepository.findByIdAndOrganizationIdAndProjectId(
                        request.environmentId(), principal.organizationId(), projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment"));
        if (environment.getType() != EnvironmentType.PRODUCTION) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOT_A_PRODUCTION_ENVIRONMENT",
                    "Production access can only be requested for a production environment.");
        }
        ProductionAccessRequest created = repository.saveAndFlush(ProductionAccessRequest.create(
                principal.organizationId(), projectId, request.environmentId(),
                principal.userId(), request.reason(), request.applicationName(),
                request.organizationDetails(), request.intendedApiUsage(), request.requestedScopes(),
                request.requestedLimits(), request.integrationInformation(),
                request.securityInformation(), clock.instant()));
        audit(principal, "production_access.requested", created.getId(),
                Map.of("projectId", projectId.toString(), "environmentId", request.environmentId().toString()));
        eventEmitter.productionAccessChanged(created, "requested");
        notifications.requestSubmitted(created);
        return toView(created);
    }

    @Transactional(readOnly = true)
    public List<ProductionAccessRequestView> list(AuthenticatedUser principal) {
        boolean reviewer = authorizationService.hasPermission(principal, Permission.PRODUCTION_ACCESS_REVIEW);
        boolean requester = authorizationService.hasPermission(principal, Permission.PRODUCTION_ACCESS_REQUEST);
        if (!reviewer && !requester) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
                    "You do not have the required permission: production_access:request");
        }
        return repository.findByOrganizationIdOrderByCreatedAtDesc(principal.organizationId()).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public ProductionAccessRequestView approve(AuthenticatedUser principal, UUID requestId,
            ProductionAccessReviewRequest review) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_ACCESS_REVIEW);
        ProductionAccessRequest request = require(principal, requestId);
        requireDifferentActor(principal, request);
        ProductionAccessStatus from = request.getStatus();
        request.approve(principal.userId(), review.note(), resolveExpiry(review), clock.instant());
        repository.saveAndFlush(request);
        recordHistory(principal, request, from, review.note());
        audit(principal, "production_access.approved", request.getId(),
                Map.of("expiresAt", String.valueOf(request.getExpiresAt())));
        eventEmitter.productionAccessChanged(request, "approved");
        notifications.approved(request);
        return toView(request);
    }

    @Transactional
    public ProductionAccessRequestView reject(AuthenticatedUser principal, UUID requestId,
            ProductionAccessReviewRequest review) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_ACCESS_REVIEW);
        ProductionAccessRequest request = require(principal, requestId);
        requireDifferentActor(principal, request);
        ProductionAccessStatus from = request.getStatus();
        request.reject(principal.userId(), review.note(), clock.instant());
        repository.saveAndFlush(request);
        recordHistory(principal, request, from, review.note());
        audit(principal, "production_access.rejected", request.getId(), Map.of());
        eventEmitter.productionAccessChanged(request, "rejected");
        notifications.rejected(request);
        return toView(request);
    }

    @Transactional
    public ProductionAccessRequestView cancel(AuthenticatedUser principal, UUID requestId) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_ACCESS_REQUEST);
        ProductionAccessRequest request = require(principal, requestId);
        ProductionAccessStatus from = request.getStatus();
        request.cancel(principal.userId(), clock.instant());
        repository.saveAndFlush(request);
        recordHistory(principal, request, from, "cancelled by requester");
        audit(principal, "production_access.cancelled", request.getId(), Map.of());
        eventEmitter.productionAccessChanged(request, "cancelled");
        return toView(request);
    }

    private void requireDifferentActor(AuthenticatedUser principal, ProductionAccessRequest request) {
        if (request.getRequestedBy().equals(principal.userId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "SELF_REVIEW_FORBIDDEN",
                    "Production access cannot be self-reviewed.");
        }
    }

    private java.time.Instant resolveExpiry(ProductionAccessReviewRequest review) {
        if (review.expiresIn() == null || review.expiresIn().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EXPIRY_REQUIRED",
                    "Approved production access must be time-boxed with expiresIn.");
        }
        Duration duration;
        try {
            duration = Duration.parse(review.expiresIn());
        } catch (RuntimeException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_EXPIRY",
                    "expiresIn must be an ISO-8601 duration no longer than 7 days.");
        }
        if (duration.isNegative() || duration.isZero() || duration.compareTo(MAX_GRANT) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_EXPIRY",
                    "expiresIn must be a positive ISO-8601 duration no longer than 7 days.");
        }
        return clock.instant().plus(duration);
    }

    private ProductionAccessRequest require(AuthenticatedUser principal, UUID requestId) {
        return repository.findByIdAndOrganizationId(requestId, principal.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Production access request"));
    }

    private void audit(AuthenticatedUser principal, String action, UUID requestId, Map<String, ?> metadata) {
        auditService.append(principal.organizationId(), principal.userId(), action,
                "production_access_request", requestId.toString(), RequestContext.currentRequestId(), metadata);
    }

    private ProductionAccessRequestView toView(ProductionAccessRequest request) {
        return new ProductionAccessRequestView(request.getId(), request.getProjectId(),
                request.getEnvironmentId(), request.getRequestedBy(), request.getStatus().name(),
                request.getReason(), request.getApplicationName(), request.getOrganizationDetails(),
                request.getIntendedApiUsage(), request.getRequestedScopes(), request.getRequestedLimits(),
                request.getIntegrationInformation(), request.getSecurityInformation(),
                request.isActiveGrant(clock.instant()),
                request.getReviewedBy(), request.getReviewedAt(), request.getExpiresAt(),
                request.getActivatedBy(), request.getActivatedAt(),
                request.getSuspensionReason(), request.getRevocationReason(),
                request.getCreatedAt());
    }

    /**
     * Claims a request for review.
     *
     * <p>Separation of duties applies here too: the requester cannot pick up their
     * own request, so "under review" genuinely means someone else is looking at it.
     */
    @Transactional
    public ProductionAccessRequestView beginReview(AuthenticatedUser principal, UUID requestId) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_ACCESS_REVIEW);
        ProductionAccessRequest request = require(principal, requestId);
        ProductionAccessStatus from = request.getStatus();
        request.beginReview(principal.userId(), clock.instant());
        repository.saveAndFlush(request);
        recordHistory(principal, request, from, null);
        audit(principal, "production_access.under_review", request.getId(), Map.of());
        eventEmitter.productionAccessChanged(request, "reviewed");
        notifications.reviewStarted(request);
        return toView(request);
    }

    /**
     * Brings an approved grant live.
     *
     * <p>Separate from approval on purpose: this is the step that asserts
     * provisioning actually succeeded, so an approved-but-unprovisioned request is
     * never reported as live.
     */
    @Transactional
    public ProductionAccessRequestView activate(AuthenticatedUser principal, UUID requestId) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_ACCESS_REVIEW);
        ProductionAccessRequest request = require(principal, requestId);
        ProductionAccessStatus from = request.getStatus();
        request.activate(principal.userId(), clock.instant());
        repository.saveAndFlush(request);
        recordHistory(principal, request, from, "provisioned and activated");
        audit(principal, "production_access.activated", request.getId(), Map.of());
        eventEmitter.productionAccessChanged(request, "activated");
        notifications.activated(request);
        return toView(request);
    }

    /** Temporarily withdraws a live grant. Reversible via reactivate. */
    @Transactional
    public ProductionAccessRequestView suspend(AuthenticatedUser principal, UUID requestId,
            String reason) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_ACCESS_REVIEW);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SUSPENSION_REASON_REQUIRED",
                    "A suspension reason is required.");
        }
        ProductionAccessRequest request = require(principal, requestId);
        ProductionAccessStatus from = request.getStatus();
        request.suspend(principal.userId(), reason, clock.instant());
        repository.saveAndFlush(request);
        recordHistory(principal, request, from, reason);
        audit(principal, "production_access.suspended", request.getId(),
                Map.of("reason", reason));
        eventEmitter.productionAccessChanged(request, "suspended");
        notifications.suspended(request);
        return toView(request);
    }

    /** Returns a suspended grant to service, still bounded by its original expiry. */
    @Transactional
    public ProductionAccessRequestView reactivate(AuthenticatedUser principal, UUID requestId) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_ACCESS_REVIEW);
        ProductionAccessRequest request = require(principal, requestId);
        ProductionAccessStatus from = request.getStatus();
        request.reactivate(principal.userId(), clock.instant());
        repository.saveAndFlush(request);
        recordHistory(principal, request, from, "reactivated after suspension");
        audit(principal, "production_access.reactivated", request.getId(), Map.of());
        eventEmitter.productionAccessChanged(request, "reactivated");
        notifications.reactivated(request);
        return toView(request);
    }

    /**
     * Permanently withdraws a grant.
     *
     * <p>Terminal: there is no path back, so a revocation cannot be quietly undone
     * by whoever performs it.
     */
    @Transactional
    public ProductionAccessRequestView revoke(AuthenticatedUser principal, UUID requestId,
            String reason) {
        authorizationService.requirePermission(principal, Permission.PRODUCTION_ACCESS_REVIEW);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REVOCATION_REASON_REQUIRED",
                    "A revocation reason is required.");
        }
        ProductionAccessRequest request = require(principal, requestId);
        ProductionAccessStatus from = request.getStatus();
        request.revoke(principal.userId(), reason, clock.instant());
        repository.saveAndFlush(request);
        recordHistory(principal, request, from, reason);
        audit(principal, "production_access.revoked", request.getId(), Map.of("reason", reason));
        eventEmitter.productionAccessChanged(request, "revoked");
        notifications.revoked(request);
        return toView(request);
    }

    /** The full trail for one request. */
    @Transactional(readOnly = true)
    public List<ProductionAccessHistoryView> history(AuthenticatedUser principal, UUID requestId) {
        require(principal, requestId);
        return historyRepository
                .findByRequestIdAndOrganizationIdOrderByRecordedAtAsc(requestId,
                        principal.organizationId())
                .stream()
                .map(ProductionAccessHistoryView::from)
                .toList();
    }

    private void recordHistory(AuthenticatedUser principal, ProductionAccessRequest request,
            ProductionAccessStatus from, String note) {
        historyRepository.save(ProductionAccessHistoryEntry.of(request.getId(),
                principal.organizationId(), from, request.getStatus(), principal.userId(),
                note, null));
    }
}
