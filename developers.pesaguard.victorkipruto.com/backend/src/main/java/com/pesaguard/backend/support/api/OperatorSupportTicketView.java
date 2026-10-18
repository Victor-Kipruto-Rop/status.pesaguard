package com.pesaguard.backend.support.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.support.domain.SupportTicket;

public record OperatorSupportTicketView(
        String id,
        UUID organizationId,
        UUID requesterId,
        String contactEmail,
        String category,
        String subject,
        String description,
        String priority,
        String status,
        String environment,
        String endpoint,
        Integer httpStatus,
        String requestId,
        String deliveryId,
        String eventType,
        String authenticationMethod,
        String errorCode,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt,
        UUID resolvedByOperator,
        String resolutionNote,
        String operatorActionReason,
        Instant closedAt) {

    public static OperatorSupportTicketView from(SupportTicket ticket) {
        return new OperatorSupportTicketView(ticket.getPublicId(), ticket.getOrganizationId(),
                ticket.getUserId(), ticket.getContactEmail(), ticket.getCategory().name(),
                ticket.getSubject(), ticket.getDescription(), ticket.getPriority().name(),
                ticket.getStatus().name(), ticket.getEnvironment(), ticket.getEndpoint(),
                ticket.getHttpStatus(), ticket.getRequestId(), ticket.getDeliveryId(),
                ticket.getEventType(), ticket.getAuthenticationMethod(), ticket.getErrorCode(),
                ticket.getCreatedAt(), ticket.getUpdatedAt(), ticket.getResolvedAt(),
                ticket.getResolvedByOperator(), ticket.getResolutionNote(),
                ticket.getOperatorActionReason(), ticket.getClosedAt());
    }
}
