package com.pesaguard.backend.workspace.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;
import com.pesaguard.backend.workspace.application.WorkspaceSearchService;

import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/search")
public class WorkspaceSearchController {

    private final WorkspaceSearchService searchService;

    public WorkspaceSearchController(WorkspaceSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    ApiResponse<List<WorkspaceSearchService.SearchResult>> search(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam @Size(min = 2, max = 100) String q) {
        return ApiResponse.of(searchService.search(principal, q));
    }
}
