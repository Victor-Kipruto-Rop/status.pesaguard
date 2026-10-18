package com.pesaguard.backend.changelog.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.changelog.application.ChangelogService;
import com.pesaguard.backend.common.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/changelog")
public class ChangelogController {

    private final ChangelogService changelogService;

    public ChangelogController(ChangelogService changelogService) {
        this.changelogService = changelogService;
    }

    @GetMapping
    ApiResponse<List<ChangelogEntryView>> published() {
        return ApiResponse.of(changelogService.published());
    }
}
