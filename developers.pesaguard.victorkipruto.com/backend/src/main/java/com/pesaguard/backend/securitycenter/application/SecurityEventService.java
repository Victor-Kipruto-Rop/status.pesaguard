package com.pesaguard.backend.securitycenter.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.api.PageResponse;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.securitycenter.domain.EmergencyResponse;
import com.pesaguard.backend.securitycenter.domain.SecurityEvent;
import com.pesaguard.backend.securitycenter.domain.SecurityEventType;
import com.pesaguard.backend.securitycenter.infrastructure.SecurityEventEntity;
import com.pesaguard.backend.securitycenter.infrastructure.SecurityEventRepository;

/**
 * Records and resolves security signals.
 *
 * <p>Two rules are enforced here rather than left to convention:
 *
 * <ul>
 *   <li><b>Detection never resolves.</b> Only a named human can move an event out
 *       of {@code OPEN}, and doing so always records who and why. A detector that
 *       closes its own findings makes its silence indistinguishable from
 *       "nothing is wrong".</li>
 *   <li><b>Every read and write is tenant-scoped.</b> The organization comes from
 *       the authenticated principal, never from a request body, so one tenant
 *       cannot read or resolve another's signals.</li>
 * </ul>
 */
@Service
public class SecurityEventService {

    private static final java.time.Duration DETECTION_DEDUPLICATION_WINDOW =
            java.time.Duration.ofMinutes(15);

    private final SecurityEventRepository repository;
    private final AuditService auditService;
    private final Clock clock;
    private final SecurityEventNotificationEmitter notifications;

    public SecurityEventService(SecurityEventRepository repository, AuditService auditService,
            Clock clock, SecurityEventNotificationEmitter notifications) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
        this.notifications = notifications;
    }

    /**
     * Records an observed signal.
     *
     * <p>Always {@code OPEN}. There is deliberately no overload that accepts a
     * resolution: making it impossible to create a pre-resolved event is what
     * guarantees every finding in the list was once genuinely open.
     */
    @Transactional
    public SecurityEvent.Record record(UUID organizationId, SecurityEventType type,
            UUID subjectId, String subjectKind, String detail) {
        return persist(organizationId, type, subjectId, subjectKind, detail);
    }

    /**
     * Coalesces repeated detector hits for one subject. Authentication and
     * authorization still run on every request; only persisted alerts are
     * bounded so retries cannot create an alert flood.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordIfNew(UUID organizationId, SecurityEventType type,
            UUID subjectId, String subjectKind, String detail) {
        if (organizationId == null || type == null || subjectId == null
                || subjectKind == null || subjectKind.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        if (repository.existsByOrganizationIdAndTypeAndSubjectIdAndSubjectKindAndDetectedAtAfter(
                organizationId, type, subjectId, subjectKind,
                now.minus(DETECTION_DEDUPLICATION_WINDOW))) {
            return;
        }
        persist(organizationId, type, subjectId, subjectKind, detail);
    }

    private SecurityEvent.Record persist(UUID organizationId, SecurityEventType type,
            UUID subjectId, String subjectKind, String detail) {
        SecurityEvent.Record event = SecurityEvent.Record.detected(organizationId, type,
                subjectId, subjectKind, detail, clock.instant());
        SecurityEvent.Record saved = toRecord(repository.save(new SecurityEventEntity(event)));
        notifications.notify(saved);
        return saved;
    }

    /**
     * Resolves a signal.
     *
     * <p>Re-resolving is refused rather than silently overwritten. The first
     * decision is part of the record, and a second attempt usually means two
     * people are looking at the same finding and have not spoken to each other.
     */
    @Transactional
    public SecurityEvent.Record resolve(AuthenticatedUser principal, UUID eventId,
            SecurityEvent.Resolution resolution, String note) {
        if (resolution == null || resolution == SecurityEvent.Resolution.OPEN) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_RESOLUTION",
                    "A resolution other than OPEN is required.");
        }
        if (principal == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Authentication is required.");
        }
        String normalised = EmergencyResponse.normaliseReason(note);

        SecurityEvent.Record event = repository.findByIdAndOrganizationId(eventId,
                principal.organizationId())
                .map(SecurityEventService::toRecord)
                .orElseThrow(() -> new ResourceNotFoundException("Security event"));

        if (event.isClosed()) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALREADY_RESOLVED",
                    "This security event has already been resolved.");
        }

        SecurityEvent.Record resolved = new SecurityEvent.Record(event.id(),
                event.organizationId(), event.type(), event.subjectId(), event.subjectKind(),
                event.detail(), event.detectedAt(), resolution, principal.userId(),
                clock.instant(), normalised);

        SecurityEvent.Record saved = toRecord(repository.save(
                new SecurityEventEntity(resolved)));
        auditService.append(principal.organizationId(), principal.userId(),
                "security_event.resolved", "security_event", eventId.toString(),
                RequestContext.currentRequestId(),
                java.util.Map.of("resolution", resolution.name(), "type", event.type().name()));
        return saved;
    }

    /** Open signals for a tenant, most recent first. */
    @Transactional(readOnly = true)
    public PageResponse<SecurityEvent.Record> openFor(AuthenticatedUser principal, int page, int size) {
        validatePaging(page, size);
        var results = repository.findByOrganizationIdAndResolutionOrderByDetectedAtDesc(
                principal.organizationId(), SecurityEvent.Resolution.OPEN, PageRequest.of(page, size));
        return PageResponse.of(results.getContent().stream().map(SecurityEventService::toRecord).toList(),
                results.getNumber(), results.getSize(), results.getTotalElements());
    }

    /** A tenant's full signal history. */
    @Transactional(readOnly = true)
    public PageResponse<SecurityEvent.Record> historyFor(AuthenticatedUser principal, int page, int size) {
        validatePaging(page, size);
        var results = repository.findByOrganizationIdOrderByDetectedAtDesc(
                principal.organizationId(), PageRequest.of(page, size));
        return PageResponse.of(results.getContent().stream().map(SecurityEventService::toRecord).toList(),
                results.getNumber(), results.getSize(), results.getTotalElements());
    }

    private static void validatePaging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SECURITY_EVENT_PAGE_INVALID",
                    "Page must be non-negative and size must be between 1 and 100.");
        }
    }
    private static SecurityEvent.Record toRecord(
            com.pesaguard.backend.securitycenter.infrastructure.SecurityEventEntity entity) {
        return new SecurityEvent.Record(entity.getId(), entity.getOrganizationId(),
                entity.getType(), entity.getSubjectId(), entity.getSubjectKind(),
                entity.getDetail(), entity.getDetectedAt(), entity.getResolution(),
                entity.getResolvedBy(), entity.getResolvedAt(),
                entity.getResolutionNote());
    }
}
