package com.pesaguard.backend.support.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.support.application.SupportCenterService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@Validated
@RequestMapping("/api/v1/support")
public class SupportCenterController {

    private final SupportCenterService service;

    public SupportCenterController(SupportCenterService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<SupportCenterView> overview() {
        return ApiResponse.of(service.overview());
    }

    @GetMapping("/categories")
    ApiResponse<List<SupportCategoryView>> categories() {
        return ApiResponse.of(service.categories());
    }

    @GetMapping("/articles")
    ApiResponse<SupportArticlePageView> articles(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int pageSize) {
        if (q != null && !q.isBlank()) {
            String query = q.trim();
            if (query.length() < 2 || query.length() > 120) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH_QUERY",
                        "Search query must contain 2 to 120 characters.");
            }
            return ApiResponse.of(service.searchArticles(query, page, pageSize));
        }
        return ApiResponse.of(service.articles(category, page, pageSize));
    }

    @GetMapping("/articles/{slug}")
    ApiResponse<SupportArticleView> article(@PathVariable String slug) {
        return ApiResponse.of(service.article(slug));
    }

    @PostMapping("/articles/{slug}/feedback")
    ApiResponse<String> feedback(
            @PathVariable String slug,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SupportFeedbackRequest request) {
        service.recordFeedback(slug, principal, request.helpful());
        return ApiResponse.of("Feedback recorded");
    }

    @GetMapping("/search")
    ApiResponse<List<SupportSearchResult>> search(
            @RequestParam @NotBlank @Size(max = 120) String q,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        String query = q.trim();
        if (query.length() < 2) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH_QUERY",
                    "Search query must contain at least 2 characters.");
        }
        return ApiResponse.of(service.search(query, principal));
    }
}
