package com.pesaguard.backend.ecosystem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Which artefact gets recommended to a developer.
 *
 * <p>Every rule here prevents the registry from pointing someone at something that
 * will not work, cannot be verified, or should not be used.
 */
class ReleaseSelectionTest {

    private static final Instant T = Instant.parse("2026-03-15T14:37:52Z");
    private static final String SUM =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private SdkRelease sdk(String version, SdkLanguage language, ReleaseChannel channel,
            ApiCompatibility compatibility) {
        return SdkRelease.publish(UUID.randomUUID(), language,
                SemanticVersion.parse(version).orElseThrow(), channel, compatibility, SUM,
                "https://docs.example", "notes", T);
    }

    private CliRelease build(String version, CliPlatform platform, CliArchitecture arch,
            ReleaseChannel channel) {
        return CliRelease.publish(UUID.randomUUID(),
                SemanticVersion.parse(version).orElseThrow(), platform, arch, channel, SUM,
                "https://downloads.example/cli", "notes", T);
    }

    @Test
    void theHighestStableVersionIsRecommended() {
        List<SdkRelease> releases = List.of(
                sdk("1.9.0", SdkLanguage.JAVA, ReleaseChannel.STABLE, ApiCompatibility.of(1, 1)),
                sdk("1.10.0", SdkLanguage.JAVA, ReleaseChannel.STABLE, ApiCompatibility.of(1, 1)),
                sdk("1.8.0", SdkLanguage.JAVA, ReleaseChannel.STABLE, ApiCompatibility.of(1, 1)));

        // 1.10.0 is newer than 1.9.0; a string comparison would get this backwards.
        assertThat(SdkRelease.recommendedFor(releases, SdkLanguage.JAVA))
                .hasValueSatisfying(release ->
                        assertThat(release.version().toString()).isEqualTo("1.10.0"));
    }

    @Test
    void aPreviewIsNeverRecommendedByAnUnqualifiedRequest() {
        List<SdkRelease> releases = List.of(
                sdk("1.9.0", SdkLanguage.JAVA, ReleaseChannel.STABLE, ApiCompatibility.of(1, 1)),
                sdk("2.0.0", SdkLanguage.JAVA, ReleaseChannel.BETA, ApiCompatibility.of(1, 1)));

        // 2.0.0 is higher, but a developer who did not opt into a beta must get 1.9.0.
        assertThat(SdkRelease.recommendedFor(releases, SdkLanguage.JAVA))
                .hasValueSatisfying(release ->
                        assertThat(release.version().toString()).isEqualTo("1.9.0"));
    }

    @Test
    void anIncompatibleSdkIsNeverRecommended() {
        List<SdkRelease> releases = List.of(
                sdk("3.0.0", SdkLanguage.JAVA, ReleaseChannel.STABLE, ApiCompatibility.of(2, 2)),
                sdk("1.0.0", SdkLanguage.JAVA, ReleaseChannel.STABLE, ApiCompatibility.of(1, 1)));

        // Against API v1 the 3.0.0 release cannot be used, so it must not be offered.
        assertThat(ReleaseSelection.recommendSdk(releases, SdkLanguage.JAVA, 1))
                .hasValueSatisfying(release ->
                        assertThat(release.version().toString()).isEqualTo("1.0.0"));
    }

    @Test
    void aDeprecatedReleaseIsNotNewlyRecommended() {
        SdkRelease deprecated = sdk("1.0.0", SdkLanguage.PYTHON, ReleaseChannel.STABLE,
                ApiCompatibility.of(1, 1))
                .deprecate(T.plusSeconds(60), "superseded by 2.0.0", "2.0.0");

        List<SdkRelease> releases = List.of(deprecated,
                sdk("1.5.0", SdkLanguage.PYTHON, ReleaseChannel.STABLE, ApiCompatibility.of(1, 1)));

        assertThat(SdkRelease.recommendedFor(releases, SdkLanguage.PYTHON))
                .hasValueSatisfying(release ->
                        assertThat(release.version().toString()).isEqualTo("1.5.0"));
    }

    @Test
    void aWithdrawnReleaseIsNeverRecommended() {
        SdkRelease withdrawn = sdk("2.0.0", SdkLanguage.GO, ReleaseChannel.STABLE,
                ApiCompatibility.of(1, 1)).withdraw(T.plusSeconds(60), "security advisory");

        List<SdkRelease> releases = List.of(withdrawn,
                sdk("1.0.0", SdkLanguage.GO, ReleaseChannel.STABLE, ApiCompatibility.of(1, 1)));

        assertThat(SdkRelease.recommendedFor(releases, SdkLanguage.GO))
                .hasValueSatisfying(release ->
                        assertThat(release.version().toString()).isEqualTo("1.0.0"));
    }

    @Test
    void aDeprecatedReleaseStaysDownloadable() {
        // Software already pinned to this version must keep resolving, or upgrading
        // it becomes impossible.
        SdkRelease deprecated = sdk("1.0.0", SdkLanguage.PHP, ReleaseChannel.STABLE,
                ApiCompatibility.of(1, 1)).deprecate(T, "superseded", "2.0.0");

        assertThat(deprecated.isDownloadable()).isTrue();
        assertThat(deprecated.isRecommended()).isFalse();
    }

    @Test
    void deprecationRequiresAReason() {
        SdkRelease release = sdk("1.0.0", SdkLanguage.JAVA, ReleaseChannel.STABLE,
                ApiCompatibility.of(1, 1));

        // A developer told to migrate deserves to know what to.
        assertThatThrownBy(() -> release.deprecate(T, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withdrawalRequiresAReason() {
        SdkRelease release = sdk("1.0.0", SdkLanguage.JAVA, ReleaseChannel.STABLE,
                ApiCompatibility.of(1, 1));

        // An unexplained withdrawal generates support load only a reason could answer.
        assertThatThrownBy(() -> release.withdraw(T, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRequestedChannelIsHonouredExactly() {
        List<CliRelease> builds = List.of(
                build("1.0.0", CliPlatform.LINUX, CliArchitecture.X86_64, ReleaseChannel.STABLE),
                build("2.0.0-rc.1", CliPlatform.LINUX, CliArchitecture.X86_64,
                        ReleaseChannel.BETA));

        // A developer who explicitly asked for a beta gets the beta.
        assertThat(ReleaseSelection.recommend(builds, CliPlatform.LINUX,
                CliArchitecture.X86_64, ReleaseChannel.BETA))
                .hasValueSatisfying(release ->
                        assertThat(release.version().toString()).isEqualTo("2.0.0-rc.1"));
    }

    @Test
    void anUnavailablePreviewFallsBackRatherThanReturningNothing() {
        // A developer on a machine with no arm64 preview should still get the stable
        // build that does exist.
        List<CliRelease> builds = List.of(
                build("1.0.0", CliPlatform.LINUX, CliArchitecture.ARM64, ReleaseChannel.STABLE));

        assertThat(ReleaseSelection.recommend(builds, CliPlatform.LINUX,
                CliArchitecture.ARM64, ReleaseChannel.BETA))
                .hasValueSatisfying(release ->
                        assertThat(release.version().toString()).isEqualTo("1.0.0"));
    }

    @Test
    void anUnknownTargetReturnsNothing() {
        // Better to say "no build for you" than to hand over the wrong binary.
        assertThat(ReleaseSelection.recommend(List.of(build("1.0.0", CliPlatform.LINUX,
                CliArchitecture.X86_64, ReleaseChannel.STABLE)), CliPlatform.WINDOWS,
                CliArchitecture.ARM64, ReleaseChannel.STABLE)).isEmpty();
    }

    @Test
    void aWithdrawnBuildIsNeverRecommended() {
        CliRelease withdrawn = build("1.0.0", CliPlatform.MACOS, CliArchitecture.ARM64,
                ReleaseChannel.STABLE).withdraw();

        assertThat(ReleaseSelection.recommend(List.of(withdrawn), CliPlatform.MACOS,
                CliArchitecture.ARM64, ReleaseChannel.STABLE)).isEmpty();
    }

    @Test
    void artefactNamesEncodeTheTargetAndArchitecture() {
        CliRelease linux = build("1.2.3", CliPlatform.LINUX, CliArchitecture.X86_64,
                ReleaseChannel.STABLE);
        CliRelease windows = build("1.2.3", CliPlatform.WINDOWS, CliArchitecture.ARM64,
                ReleaseChannel.STABLE);

        assertThat(linux.artefactFileName()).isEqualTo("pesaguard-cli-1.2.3-linux-amd64");
        assertThat(windows.artefactFileName()).isEqualTo("pesaguard-cli-1.2.3-windows-arm64.exe");
    }

    @Test
    void aReleaseGateFailsWhenALanguageHasNoCompatibleSdk() {
        // Shipping an API major no SDK supports would break every developer on it.
        List<SdkRelease> releases = List.of(
                sdk("1.0.0", SdkLanguage.JAVA, ReleaseChannel.STABLE, ApiCompatibility.of(1, 1)));

        assertThat(ReleaseSelection.isFullySupported(releases, 1)).isFalse();
        assertThat(ReleaseSelection.unsupportedLanguages(releases, 1))
                .contains(SdkLanguage.PYTHON, SdkLanguage.TYPESCRIPT, SdkLanguage.GO,
                        SdkLanguage.PHP, SdkLanguage.DOTNET)
                .doesNotContain(SdkLanguage.JAVA);
    }

    @Test
    void everyLanguageSupportedMakesTheGatePass() {
        List<SdkRelease> releases = java.util.Arrays.stream(SdkLanguage.values())
                .map(language -> sdk("1.0.0", language, ReleaseChannel.STABLE,
                        ApiCompatibility.of(1, 1)))
                .toList();

        assertThat(ReleaseSelection.isFullySupported(releases, 1)).isTrue();
    }

    @Test
    void anAbsurdCompatibilityRangeIsRejected() {
        // A wide range is nearly always a metadata mistake, and a developer who
        // finds out at integration time blames the platform.
        assertThatThrownBy(() -> ApiCompatibility.of(1, 99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInvertedCompatibilityRangeIsRejected() {
        assertThatThrownBy(() -> ApiCompatibility.of(3, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}