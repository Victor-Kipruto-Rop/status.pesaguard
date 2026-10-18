package com.pesaguard.backend.ecosystem.api;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.ecosystem.application.EcosystemReleaseService;
import com.pesaguard.backend.ecosystem.domain.CliArchitecture;
import com.pesaguard.backend.ecosystem.domain.CliPlatform;
import com.pesaguard.backend.ecosystem.domain.CliRelease;
import com.pesaguard.backend.ecosystem.domain.ReleaseChannel;
import com.pesaguard.backend.ecosystem.domain.SdkLanguage;
import com.pesaguard.backend.ecosystem.domain.SdkRelease;

/** Developer-facing metadata registry. It never proxies artefact downloads. */
@RestController
@RequestMapping("/api/v1/ecosystem")
public class EcosystemController {
    private final EcosystemReleaseService service;

    public EcosystemController(EcosystemReleaseService service) { this.service = service; }

    @GetMapping("/sdk-releases")
    ApiResponse<List<SdkReleaseView>> sdkReleases() {
        return ApiResponse.of(service.sdkReleases().stream().map(this::sdkView).toList());
    }

    @GetMapping("/sdk-releases/{language}/recommended")
    ApiResponse<SdkReleaseView> recommendedSdk(@PathVariable SdkLanguage language,
            @RequestParam(defaultValue = "1") int apiMajor) {
        return ApiResponse.of(sdkView(service.recommendedSdk(language, apiMajor)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No compatible supported SDK release is available."))));
    }

    @GetMapping("/cli-releases")
    ApiResponse<List<CliReleaseView>> cliReleases() {
        return ApiResponse.of(service.cliReleases().stream().map(this::cliView).toList());
    }

    @GetMapping("/cli-releases/recommended")
    ApiResponse<CliReleaseView> recommendedCli(@RequestParam CliPlatform platform,
            @RequestParam CliArchitecture architecture,
            @RequestParam(defaultValue = "STABLE") ReleaseChannel channel) {
        return ApiResponse.of(cliView(service.recommendedCli(platform, architecture, channel)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No downloadable CLI release is available for this target."))));
    }

    private SdkReleaseView sdkView(SdkRelease release) {
        return new SdkReleaseView(release.language().name(), release.language().displayName(), release.version().toString(),
                release.channel().name(), release.status().name(), release.compatibility().minimumApiMajor(),
                release.compatibility().maximumApiMajor(), release.checksum(), release.documentationUrl(), release.releaseNotes(),
                release.publishedAt(), release.deprecatedAt(), release.deprecationReason(), release.supersededBy());
    }

    private CliReleaseView cliView(CliRelease release) {
        return new CliReleaseView(release.version().toString(), release.platform().name(), release.architecture().name(),
                release.channel().name(), release.status().name(), release.checksum(), release.downloadUrl(),
                release.artefactFileName(), release.releaseNotes(), release.publishedAt());
    }

    record SdkReleaseView(String language, String languageDisplayName, String version, String channel, String status,
            int minimumApiMajor, int maximumApiMajor, String checksum, String documentationUrl, String releaseNotes,
            Instant publishedAt, Instant deprecatedAt, String deprecationReason, String supersededBy) { }
    record CliReleaseView(String version, String platform, String architecture, String channel, String status,
            String checksum, String downloadUrl, String artefactFileName, String releaseNotes, Instant publishedAt) { }
}
