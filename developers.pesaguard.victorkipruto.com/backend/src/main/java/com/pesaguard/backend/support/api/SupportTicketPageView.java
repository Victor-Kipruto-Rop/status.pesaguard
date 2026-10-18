package com.pesaguard.backend.support.api;

import java.util.List;

public record SupportTicketPageView(
        List<SupportTicketView> tickets,
        int page,
        int pageSize,
        long totalItems,
        int totalPages) {
}
