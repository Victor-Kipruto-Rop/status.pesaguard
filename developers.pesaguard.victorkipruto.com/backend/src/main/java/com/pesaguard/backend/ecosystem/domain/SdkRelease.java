package com.pesaguard.backend.ecosystem.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A published SDK release.
 *
 * <p>Metadata only. The artefact itself lives in a package registry and the
 * source in a separate repository; this records enough for a developer to choose
 * a version and verify what they downloaded.
 *
 * <p>Immutable once published. A release is a fact about what was shipped, and
 * editing one retroactively would invalidate every checksum a developer verified
 * against it. Changes are made by publishing a new version.
 */
public record SdkRelease(
        UUID id,
        SdkLanguage language,
        SemanticVersion version,
        ReleaseChannel channel,
        ReleaseStatus status,
        ApiCompatibility compatibility,
        String checksum,
        String documentationUrl,
        String releaseNotes,
        Instant publishedAt,
        Instant deprecatedAt,
        String deprecationReason,
        String supersededBy) {

    /**
     * Creates a published release.
     *
     * <p>Validation here is what keeps a registry from publishing something a
     * developer cannot use: an unparseable version, a malformed checksum, or a
     * release that claims to work with no API at all.
     */
    public static SdkRelease publish(UUID id, SdkLanguage language, SemanticVersion version,
            ReleaseChannel channel, ApiCompatibility compatibility, String checksum,
            String documentationUrl, String releaseNotes, Instant publishedAt) {
        if (language == null || version == null || channel == null || compatibility == null) {
            throw new IllegalArgumentException(
                    "language, version, channel, and compatibility are required");
        }
        if (publishedAt == null) {
            throw new IllegalArgumentException("publishedAt is required");
        }
        // A checksum a developer cannot verify against is worse than none.
        String normalised = ArtifactChecksum.normalise(checksum);
        return new SdkRelease(id, language, version, channel, ReleaseStatus.PUBLISHED,
                compatibility, normalised, documentationUrl, releaseNotes, publishedAt,
                null, null, null);
    }

    /**
     * Marks the release deprecated.
     *
     * <p>Deprecated is not withdrawn. The artefact keeps resolving so software
     * already pinned to it does not break; only advertising stops.
     */
    public SdkRelease deprecate(Instant when, String reason, String supersededBy) {
        if (status == ReleaseStatus.WITHDRAWN) {
            throw new IllegalStateException("A withdrawn release cannot be deprecated");
        }
        if (reason == null || reason.isBlank()) {
            // A developer told to migrate deserves to know what to.
            throw new IllegalArgumentException("A deprecation reason is required");
        }
        if (when == null) {
            throw new IllegalArgumentException("A deprecation instant is required");
        }
        return new SdkRelease(id, language, version, channel, ReleaseStatus.DEPRECATED,
                compatibility, checksum, documentationUrl, releaseNotes, publishedAt, when,
                reason.trim(), supersededBy);
    }

    /**
     * Withdraws the release entirely.
     *
     * <p>For a release that must stop being used, such as one with a known
     * vulnerability. A reason is mandatory: an unexplained withdrawal is
     * indistinguishable from a packaging mistake and generates support load that
     * only the reason could have answered.
     */
    public SdkRelease withdraw(Instant when, String reason) {
        if (status == ReleaseStatus.WITHDRAWN) {
            throw new IllegalStateException("This release is already withdrawn");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A withdrawal reason is required");
        }
        return new SdkRelease(id, language, version, channel, ReleaseStatus.WITHDRAWN,
                compatibility, checksum, documentationUrl, releaseNotes, publishedAt,
                deprecatedAt, deprecationReason, supersededBy);
    }

    public boolean isDeprecated() {
        return status == ReleaseStatus.DEPRECATED;
    }

    public boolean isDownloadable() {
        return status.isDownloadable();
    }

    /**
     * Whether a developer should be pointed at this release by default.
     *
     * <p>Requires: downloadable, on a supported channel, and not deprecated. A
     * deprecated release is deliberately excluded so nobody is newly sent to one.
     */
    public boolean isRecommended() {
        return status.isDownloadable() && channel.isSupported() && !isDeprecated();
    }

    /** Whether this release works against a platform API major. */
    public boolean supportsApi(int apiMajor) {
        return compatibility.supports(apiMajor);
    }

    /**
     * The newest recommended release for a language.
     *
     * <p>Among recommended releases only. This is the function that decides what
     * a developer is told to install, so it excludes withdrawn, deprecated, and
     * preview channels rather than ranking them.
     */
    public static Optional<SdkRelease> recommendedFor(List<SdkRelease> releases,
            SdkLanguage language) {
        return releases.stream()
                .filter(release -> release.language() == language)
                .filter(SdkRelease::isRecommended)
                .max(Comparator.comparing(SdkRelease::version));
    }

    /**
     * The newest downloadable release on any supported channel.
     *
     * <p>Used when a developer explicitly asks for the newest build, including
     * previews. Distinct from {@link #recommendedFor} so a preview can never be
     * returned by an unqualified "latest".
     */
    public static Optional<SdkRelease> latestFor(List<SdkRelease> releases,
            SdkLanguage language) {
        return releases.stream()
                .filter(release -> release.language() == language)
                .filter(release -> release.status().isDownloadable())
                .max(Comparator.comparing(SdkRelease::version));
    }
}