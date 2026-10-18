package com.pesaguard.backend.support.api;

import java.time.Instant;
import com.pesaguard.backend.support.domain.SupportTicket;

public record SupportTicketView(
        String id,
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
        Instant closedAt) {

    public static SupportTicketView from(SupportTicket ticket) {
        return new SupportTicketView(ticket.getPublicId(), ticket.getCategory().name(),
                ticket.getSubject(), ticket.getDescription(), ticket.getPriority().name(),
                ticket.getStatus().name(), ticket.getEnvironment(), ticket.getEndpoint(),
                ticket.getHttpStatus(), ticket.getRequestId(), ticket.getDeliveryId(),
                ticket.getEventType(), ticket.getAuthenticationMethod(), ticket.getErrorCode(),
                ticket.getCreatedAt(), ticket.getUpdatedAt(), ticket.getResolvedAt(), ticket.getClosedAt());
    }
}
