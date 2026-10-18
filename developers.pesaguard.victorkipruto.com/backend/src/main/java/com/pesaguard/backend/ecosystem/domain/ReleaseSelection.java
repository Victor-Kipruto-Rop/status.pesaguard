package com.pesaguard.backend.ecosystem.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Chooses which artefact to recommend.
 *
 * <p>Every rule here exists because the alternative is recommending the wrong
 * thing to a developer, which in an SDK registry is a support incident rather
 * than a cosmetic error.
 */
public final class ReleaseSelection {

    private ReleaseSelection() {
    }

    /**
     * The CLI build to recommend for a platform, architecture, and channel.
     *
     * <p>Precedence, in order:
     *
     * <ol>
     *   <li>withdrawn and deprecated builds are excluded;</li>
     *   <li>the requested channel is honoured exactly — a developer who asked for
     *       a preview gets the preview, not the stable release;</li>
     *   <li>within a channel, the highest version wins;</li>
     *   <li>otherwise the most stable available channel, so a request for an
     *       unavailable preview falls back to stable rather than to nothing.</li>
     * </ol>
     */
    public static Optional<CliRelease> recommend(List<CliRelease> builds, CliPlatform platform,
            CliArchitecture architecture, ReleaseChannel requestedChannel) {
        List<CliRelease> candidates = builds.stream()
                .filter(build -> build.platform() == platform)
                .filter(build -> build.architecture() == architecture)
                // Not isRecommended(): that requires a supported channel, which
                // would make an explicit request for a beta impossible to satisfy.
                .filter(build -> build.isDownloadable())
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Optional<CliRelease> exact = newest(candidates, requestedChannel);
        if (exact.isPresent()) {
            return exact;
        }
        // Fall back down the stability order rather than returning nothing: a
        // developer on an older machine with no arm64 preview should still get the
        // stable build that does exist.
        for (ReleaseChannel channel : ReleaseChannel.values()) {
            if (!channel.isMoreStableThan(requestedChannel)) {
                continue;
            }
            Optional<CliRelease> fallback = newest(candidates, channel);
            if (fallback.isPresent()) {
                return fallback;
            }
        }
        return Optional.empty();
    }

    private static Optional<CliRelease> newest(List<CliRelease> candidates,
            ReleaseChannel channel) {
        return candidates.stream()
                .filter(build -> build.channel() == channel)
                .max(Comparator.comparing(CliRelease::version));
    }

    /**
     * The SDK release to recommend for a language and platform API major.
     *
     * <p>Compatibility is a hard filter, applied before the version comparison.
     * Recommending an SDK that cannot talk to the API is worse than recommending
     * nothing, because it fails later and further from its cause.
     */
    public static Optional<SdkRelease> recommendSdk(List<SdkRelease> releases,
            SdkLanguage language, int apiMajor) {
        return releases.stream()
                .filter(release -> release.language() == language)
                .filter(release -> release.supportsApi(apiMajor))
                .filter(SdkRelease::isRecommended)
                .max(Comparator.comparing(SdkRelease::version));
    }

    /**
     * Whether every language has a release supporting an API major.
     *
     * <p>Used as a release gate: shipping an API major that no SDK supports would
     * break every developer on it, so this is checked before the platform
     * version is published rather than discovered afterwards.
     */
    public static boolean isFullySupported(List<SdkRelease> releases, int apiMajor) {
        for (SdkLanguage language : SdkLanguage.values()) {
            boolean any = releases.stream()
                    .anyMatch(release -> release.language() == language
                            && release.supportsApi(apiMajor)
                            && release.isRecommended());
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /**
     * Languages with no recommended release for an API major.
     *
     * <p>The detail behind {@link #isFullySupported}, so a failed gate says which
     * language is the problem.
     */
    public static List<SdkLanguage> unsupportedLanguages(List<SdkRelease> releases,
            int apiMajor) {
        return java.util.Arrays.stream(SdkLanguage.values())
                .filter(language -> releases.stream()
                        .noneMatch(release -> release.language() == language
                                && release.supportsApi(apiMajor)
                                && release.isRecommended()))
                .toList();
    }
}
