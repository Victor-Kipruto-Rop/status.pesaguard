package com.pesaguard.backend.support.api;

import java.util.List;

public record OperatorSupportTicketPageView(
        List<OperatorSupportTicketView> items,
        int page,
        int pageSize,
        long totalElements,
        int totalPages) {
}
