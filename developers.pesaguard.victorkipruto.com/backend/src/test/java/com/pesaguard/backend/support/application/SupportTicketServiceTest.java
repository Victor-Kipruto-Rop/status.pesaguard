package com.pesaguard.backend.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import com.pesaguard.backend.audit.application.AuditService;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.notifications.application.NotificationService;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.support.api.CreateSupportTicketRequest;
import com.pesaguard.backend.support.domain.SupportTicket;
import com.pesaguard.backend.support.domain.SupportTicketCategory;
import com.pesaguard.backend.support.domain.SupportTicketPriority;
import com.pesaguard.backend.support.domain.SupportTicketStatus;
import com.pesaguard.backend.support.infrastructure.SupportTicketRepository;

@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @Mock
    private SupportTicketRepository repository;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private SupportTicketEmailNotifier emailNotifier;

    private SupportTicketService service;
    private AuthenticatedUser principal;

    @BeforeEach
    void setUp() {
        service = new SupportTicketService(repository, auditService,
                Clock.fixed(NOW, ZoneOffset.UTC), notificationService, emailNotifier);
        principal = new AuthenticatedUser(userId, organizationId, sessionId,
                "developer@example.com", "Developer", Set.of());
    }

    @Test
    void createStoresRequestForAuthenticatedUserAndAuditsWithoutIncludingDescription() {
        when(repository.saveAndFlush(any(SupportTicket.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var view = service.create(principal, new CreateSupportTicketRequest(
                SupportTicketCategory.API_ISSUE, "  Request failed  ",
                "  The sandbox request returned an unexpected error.  ", SupportTicketPriority.HIGH,
                "SANDBOX", "/v1/transactions", 401, "req_abcd1234", null, null, null, "INVALID_API_KEY"));

        ArgumentCaptor<SupportTicket> ticketCaptor = ArgumentCaptor.forClass(SupportTicket.class);
        verify(repository).saveAndFlush(ticketCaptor.capture());
        SupportTicket saved = ticketCaptor.getValue();
        assertThat(saved.getOrganizationId()).isEqualTo(organizationId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getContactEmail()).isEqualTo("developer@example.com");
        assertThat(saved.getSubject()).isEqualTo("Request failed");
        assertThat(saved.getDescription()).isEqualTo("The sandbox request returned an unexpected error.");
        assertThat(saved.getStatus()).isEqualTo(SupportTicketStatus.OPEN);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(view.id()).isEqualTo(saved.getPublicId());
        assertThat(saved.getRequestId()).isEqualTo("req_abcd1234");
        assertThat(saved.getHttpStatus()).isEqualTo(401);
        verify(auditService).append(eq(organizationId), eq(userId), eq("support.ticket.created"),
                eq("support_ticket"), eq(saved.getId().toString()), any(),
                eq(java.util.Map.of("category", "API_ISSUE", "priority", "HIGH")));
    }

    @Test
    void listScopesTicketsToTheCurrentOrganizationAndUser() {
        when(repository.findByOrganizationIdAndUserIdOrderByUpdatedAtDesc(
                organizationId, userId, PageRequest.of(0, 20)))
                .thenReturn(Page.empty());

        assertThat(service.list(principal, 0, 20).tickets()).isEmpty();

        verify(repository).findByOrganizationIdAndUserIdOrderByUpdatedAtDesc(
                organizationId, userId, PageRequest.of(0, 20));
    }

    @Test
    void getDoesNotRevealTicketsOwnedByAnotherUserOrOrganization() {
        String publicId = "SUP-1234567890";
        when(repository.findByPublicIdAndOrganizationIdAndUserId(publicId, organizationId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(principal, publicId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void closeAndReopenUsePublicReferenceAndAuditBothTransitions() {
        SupportTicket ticket = SupportTicket.open(organizationId, userId, principal.email(),
                SupportTicketCategory.API, "Request failed", "The request failed in sandbox.",
                SupportTicketPriority.NORMAL,
                new CreateSupportTicketRequest(SupportTicketCategory.API, "Request failed",
                        "The request failed in sandbox.", SupportTicketPriority.NORMAL,
                        "SANDBOX", null, null, null, null, null, null, null),
                NOW);
        when(repository.findByPublicIdAndOrganizationIdAndUserId(
                ticket.getPublicId(), organizationId, userId)).thenReturn(Optional.of(ticket));
        when(repository.saveAndFlush(any(SupportTicket.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var closed = service.close(principal, ticket.getPublicId());
        var reopened = service.reopen(principal, ticket.getPublicId());

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closedAt()).isEqualTo(NOW);
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(reopened.closedAt()).isNull();
        verify(auditService).append(eq(organizationId), eq(userId), eq("support.ticket.closed"),
                eq("support_ticket"), eq(ticket.getId().toString()), any(),
                eq(java.util.Map.of("publicId", ticket.getPublicId())));
        verify(auditService).append(eq(organizationId), eq(userId), eq("support.ticket.reopened"),
                eq("support_ticket"), eq(ticket.getId().toString()), any(),
                eq(java.util.Map.of("publicId", ticket.getPublicId())));
    }

    @Test
    void ordinaryDevelopersCannotMarkTicketsUrgent() {
        var request = new CreateSupportTicketRequest(SupportTicketCategory.API, "Urgent request",
                "A request that asks for urgent priority.", SupportTicketPriority.URGENT,
                null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(principal, request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void operatorCanResolveTicketAndResolutionIsDurableAndNotified() {
        SupportTicket ticket = SupportTicket.open(organizationId, userId, principal.email(),
                SupportTicketCategory.API, "Request failed", "The request failed in sandbox.",
                SupportTicketPriority.NORMAL,
                new CreateSupportTicketRequest(SupportTicketCategory.API, "Request failed",
                        "The request failed in sandbox.", SupportTicketPriority.NORMAL,
                        "SANDBOX", null, null, null, null, null, null, null),
                NOW);
        AuthenticatedOperator operator = new AuthenticatedOperator(UUID.randomUUID(), "support-operator",
                Set.of(OperatorCapability.SUPPORT_RESOLVE), "Verified fix deployed");
        when(repository.findByPublicIdForUpdate(ticket.getPublicId())).thenReturn(Optional.of(ticket));
        when(repository.saveAndFlush(any(SupportTicket.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var resolved = service.resolveForOperator(operator, ticket.getPublicId(), "Updated the affected service.");

        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolutionNote()).isEqualTo("Updated the affected service.");
        assertThat(resolved.resolvedByOperator()).isEqualTo(operator.operatorId());
        assertThat(resolved.operatorActionReason()).isEqualTo("Verified fix deployed");
        verify(notificationService).enqueue(eq(organizationId), eq(userId),
                eq(com.pesaguard.backend.notifications.domain.NotificationType.SUPPORT_TICKET_RESOLVED),
                eq("Support request resolved"), any(), eq("support_ticket"),
                any(), isNull(), any());
    }
}
