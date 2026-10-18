package com.pesaguard.backend.support.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.ResourceConflictException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.support.api.CreateSupportTicketRequest;
import com.pesaguard.backend.support.api.OperatorSupportTicketPageView;
import com.pesaguard.backend.support.api.OperatorSupportTicketView;
import com.pesaguard.backend.support.api.SupportTicketView;
import com.pesaguard.backend.support.api.SupportTicketPageView;
import com.pesaguard.backend.support.domain.SupportTicket;
import com.pesaguard.backend.support.domain.SupportTicketPriority;
import com.pesaguard.backend.support.domain.SupportTicketStatus;
import com.pesaguard.backend.support.infrastructure.SupportTicketRepository;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;
import com.pesaguard.backend.notifications.application.NotificationService;
import com.pesaguard.backend.notifications.domain.NotificationType;

@Service
public class SupportTicketService {

    private final SupportTicketRepository repository;
    private final AuditService auditService;
    private final Clock clock;
    private final NotificationService notificationService;
    private final SupportTicketEmailNotifier emailNotifier;

    public SupportTicketService(SupportTicketRepository repository, AuditService auditService, Clock clock,
            NotificationService notificationService, SupportTicketEmailNotifier emailNotifier) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
        this.notificationService = notificationService;
        this.emailNotifier = emailNotifier;
    }

    @Transactional
    public SupportTicketView create(AuthenticatedUser principal, CreateSupportTicketRequest request) {
        emailNotifier.requireConfigured();
        if (request.priority() == SupportTicketPriority.URGENT
                && !principal.authorities().contains("support:priority:urgent")
                && !principal.authorities().contains("support:manage")) {
            throw new AccessDeniedException("Urgent priority requires support entitlement.");
        }
        SupportTicket ticket = repository.saveAndFlush(SupportTicket.open(
                principal.organizationId(), principal.userId(), principal.email(),
                request.category(), request.subject(), SensitiveDataRedactor.redact(request.description()),
                request.priority(), request, clock.instant()));
        emailNotifier.notifyNewTicket(ticket);
        auditService.append(principal.organizationId(), principal.userId(), "support.ticket.created",
                "support_ticket", ticket.getId().toString(), RequestContext.currentRequestId(),
                Map.of("category", ticket.getCategory().name(), "priority", ticket.getPriority().name()));
        notificationService.enqueue(ticket.getOrganizationId(), ticket.getUserId(),
                NotificationType.SUPPORT_TICKET_CREATED, "Support request received",
                "We received support request " + ticket.getPublicId() + ": " + ticket.getSubject(),
                "support_ticket", ticket.getPublicId(), null,
                ticket.getPublicId() + ":created");
        return SupportTicketView.from(ticket);
    }

    @Transactional(readOnly = true)
    public SupportTicketPageView list(AuthenticatedUser principal, int page, int pageSize) {
        var result = repository.findByOrganizationIdAndUserIdOrderByUpdatedAtDesc(
                principal.organizationId(), principal.userId(), PageRequest.of(page, pageSize));
        return new SupportTicketPageView(result.getContent().stream()
                .map(SupportTicketView::from).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public SupportTicketView get(AuthenticatedUser principal, String ticketId) {
        return SupportTicketView.from(ownedTicket(principal, ticketId));
    }

    @Transactional
    public SupportTicketView close(AuthenticatedUser principal, String ticketId) {
        SupportTicket ticket = ownedTicket(principal, ticketId);
        if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
            throw new ResourceConflictException("SUPPORT_TICKET_ALREADY_CLOSED",
                    "Support ticket is already closed.");
        }
        ticket.close(clock.instant());
        SupportTicket saved = repository.saveAndFlush(ticket);
        auditService.append(principal.organizationId(), principal.userId(), "support.ticket.closed",
                "support_ticket", saved.getId().toString(), RequestContext.currentRequestId(),
                Map.of("publicId", saved.getPublicId()));
        return SupportTicketView.from(saved);
    }

    @Transactional
    public SupportTicketView reopen(AuthenticatedUser principal, String ticketId) {
        SupportTicket ticket = ownedTicket(principal, ticketId);
        if (ticket.getStatus() != SupportTicketStatus.CLOSED
                && ticket.getStatus() != SupportTicketStatus.RESOLVED) {
            throw new ResourceConflictException("SUPPORT_TICKET_NOT_REOPENABLE",
                    "Only resolved or closed support tickets can be reopened.");
        }
        ticket.reopen(clock.instant());
        SupportTicket saved = repository.saveAndFlush(ticket);
        auditService.append(principal.organizationId(), principal.userId(), "support.ticket.reopened",
                "support_ticket", saved.getId().toString(), RequestContext.currentRequestId(),
                Map.of("publicId", saved.getPublicId()));
        return SupportTicketView.from(saved);
    }

    @Transactional(readOnly = true)
    public List<SupportTicketView> search(AuthenticatedUser principal, String query) {
        return repository.searchOwnedTickets(principal.organizationId(), principal.userId(), query, PageRequest.of(0, 10))
                .stream().map(SupportTicketView::from).toList();
    }

    @Transactional(readOnly = true)
    public OperatorSupportTicketPageView listForOperator(AuthenticatedOperator operator, int page, int pageSize) {
        operator.require(OperatorCapability.SUPPORT_READ);
        var result = repository.findAllByOrderByUpdatedAtDesc(PageRequest.of(page, pageSize));
        return new OperatorSupportTicketPageView(result.getContent().stream()
                .map(OperatorSupportTicketView::from).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public OperatorSupportTicketView resolveForOperator(AuthenticatedOperator operator,
            String publicId, String resolutionNote) {
        operator.require(OperatorCapability.SUPPORT_RESOLVE);
        String reason = operator.requireReason();
        if (resolutionNote == null || resolutionNote.isBlank() || resolutionNote.trim().length() > 1000) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SUPPORT_TICKET_RESOLUTION",
                    "A resolution note of up to 1,000 characters is required.");
        }
        SupportTicket ticket = repository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Support ticket"));
        try {
            ticket.resolve(operator.operatorId(), resolutionNote.trim(), reason, clock.instant());
        } catch (IllegalStateException conflict) {
            throw new ResourceConflictException("SUPPORT_TICKET_NOT_RESOLVABLE",
                    "Only an open support ticket can be resolved.");
        }
        SupportTicket saved = repository.saveAndFlush(ticket);
        notificationService.enqueue(saved.getOrganizationId(), saved.getUserId(),
                NotificationType.SUPPORT_TICKET_RESOLVED, "Support request resolved",
                "Support request " + saved.getPublicId() + " has been resolved.\n\n"
                        + saved.getResolutionNote(),
                "support_ticket", saved.getPublicId(), null,
                saved.getPublicId() + ":resolved");
        return OperatorSupportTicketView.from(saved);
    }

    private SupportTicket ownedTicket(AuthenticatedUser principal, String publicId) {
        return repository.findByPublicIdAndOrganizationIdAndUserId(
                        publicId, principal.organizationId(), principal.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Support ticket"));
    }
}
