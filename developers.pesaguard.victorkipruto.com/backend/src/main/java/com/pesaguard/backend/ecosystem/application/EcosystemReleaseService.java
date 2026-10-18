package com.pesaguard.backend.ecosystem.application;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.ecosystem.domain.CliArchitecture;
import com.pesaguard.backend.ecosystem.domain.CliPlatform;
import com.pesaguard.backend.ecosystem.domain.CliRelease;
import com.pesaguard.backend.ecosystem.domain.ReleaseChannel;
import com.pesaguard.backend.ecosystem.domain.ReleaseSelection;
import com.pesaguard.backend.ecosystem.domain.SdkLanguage;
import com.pesaguard.backend.ecosystem.domain.SdkRelease;
import com.pesaguard.backend.ecosystem.infrastructure.CliReleaseRepository;
import com.pesaguard.backend.ecosystem.infrastructure.SdkReleaseRepository;

/** Public read facade for release metadata written by the trusted release pipeline. */
@Service
public class EcosystemReleaseService {
    private final SdkReleaseRepository sdkRepository;
    private final CliReleaseRepository cliRepository;

    public EcosystemReleaseService(SdkReleaseRepository sdkRepository, CliReleaseRepository cliRepository) {
        this.sdkRepository = sdkRepository;
        this.cliRepository = cliRepository;
    }

    @Transactional(readOnly = true)
    public List<SdkRelease> sdkReleases() {
        return sdkRepository.findAllByOrderByPublishedAtDesc().stream().map(entity -> entity.toDomain()).toList();
    }

    @Transactional(readOnly = true)
    public Optional<SdkRelease> recommendedSdk(SdkLanguage language, int apiMajor) {
        if (apiMajor < 1) throw new IllegalArgumentException("API major must be positive.");
        return ReleaseSelection.recommendSdk(sdkRepository.findByLanguage(language).stream()
                .map(entity -> entity.toDomain()).toList(), language, apiMajor);
    }

    @Transactional(readOnly = true)
    public List<CliRelease> cliReleases() {
        return cliRepository.findAllByOrderByPublishedAtDesc().stream().map(entity -> entity.toDomain()).toList();
    }

    @Transactional(readOnly = true)
    public Optional<CliRelease> recommendedCli(CliPlatform platform, CliArchitecture architecture,
            ReleaseChannel channel) {
        return ReleaseSelection.recommend(cliReleases(), platform, architecture, channel);
    }
}
