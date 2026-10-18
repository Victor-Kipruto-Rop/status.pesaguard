package com.pesaguard.backend.changelog.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.changelog.application.ChangelogService;
import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/configuration/changelog")
public class OperatorChangelogController {

    private final ChangelogService changelogService;

    public OperatorChangelogController(ChangelogService changelogService) {
        this.changelogService = changelogService;
    }

    @GetMapping
    ApiResponse<List<ChangelogEntryView>> drafts(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return ApiResponse.of(changelogService.drafts(operator));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ChangelogEntryView> create(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody ChangelogEntryRequest request) {
        return ApiResponse.of(changelogService.createDraft(request, operator));
    }

    @PutMapping("/{entryId}")
    ApiResponse<ChangelogEntryView> updateDraft(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID entryId,
            @Valid @RequestBody ChangelogEntryRequest request) {
        return ApiResponse.of(changelogService.updateDraft(entryId, request, operator));
    }

    @PostMapping("/{entryId}/publish")
    ApiResponse<ChangelogEntryView> publish(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID entryId) {
        return ApiResponse.of(changelogService.publish(entryId, operator));
    }
}
