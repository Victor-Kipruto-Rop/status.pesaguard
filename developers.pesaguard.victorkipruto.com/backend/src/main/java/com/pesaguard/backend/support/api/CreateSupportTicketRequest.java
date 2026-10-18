package com.pesaguard.backend.support.api;

import com.pesaguard.backend.support.domain.SupportTicketCategory;
import com.pesaguard.backend.support.domain.SupportTicketPriority;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateSupportTicketRequest(
        @NotNull SupportTicketCategory category,
        @NotBlank @Size(max = 180) String subject,
        @NotBlank @Size(max = 8000) String description,
        @NotNull SupportTicketPriority priority,
        @Pattern(regexp = "^(|SANDBOX|PRODUCTION)$") String environment,
        @Pattern(regexp = "^(|/(?!/)[A-Za-z0-9_{}./-]{1,200})$") String endpoint,
        @Min(100) @Max(599) Integer httpStatus,
        @Pattern(regexp = "^(|req_[A-Za-z0-9_-]{4,80})$") String requestId,
        @Pattern(regexp = "^[A-Za-z0-9_.:-]{0,100}$") String deliveryId,
        @Pattern(regexp = "^[A-Za-z0-9_.:-]{0,100}$") String eventType,
        @Pattern(regexp = "^[A-Za-z0-9 _.-]{0,60}$") String authenticationMethod,
        @Pattern(regexp = "^[A-Za-z0-9_.:-]{0,80}$") String errorCode) {
}
