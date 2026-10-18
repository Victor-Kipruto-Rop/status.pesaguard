package com.pesaguard.backend.common.api;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(List.copyOf(items), page, size, totalElements, totalPages);
    }
}
