package com.pesaguard.backend.ecosystem.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A published CLI build for one platform and architecture.
 *
 * <p>Metadata only: the binary is built and stored by the release pipeline, and
 * this records enough for a developer to pick the right artefact and verify it.
 *
 * <p>One record per platform/architecture pair, not one per release, because
 * checksums are per artefact. A single checksum across platforms would be
 * meaningless, and getting that wrong is how a developer ends up verifying the
 * Windows binary and trusting the Linux one.
 */
public record CliRelease(
        UUID id,
        SemanticVersion version,
        CliPlatform platform,
        CliArchitecture architecture,
        ReleaseChannel channel,
        ReleaseStatus status,
        String checksum,
        String downloadUrl,
        String releaseNotes,
        Instant publishedAt) {

    public CliRelease {
        if (version == null || platform == null || architecture == null) {
            throw new IllegalArgumentException(
                    "version, platform, and architecture are required");
        }
        if (channel == null) {
            throw new IllegalArgumentException("channel is required");
        }
        if (publishedAt == null) {
            throw new IllegalArgumentException("publishedAt is required");
        }
        // Validated on construction: a checksum that cannot be verified against is
        // the one value standing between a developer and a tampered download.
        checksum = ArtifactChecksum.normalise(checksum);
    }

    public static CliRelease publish(UUID id, SemanticVersion version, CliPlatform platform,
            CliArchitecture architecture, ReleaseChannel channel, String checksum,
            String downloadUrl, String releaseNotes, Instant publishedAt) {
        return new CliRelease(id, version, platform, architecture, channel,
                ReleaseStatus.PUBLISHED, checksum, downloadUrl, releaseNotes, publishedAt);
    }

    public boolean isDownloadable() {
        return status.isDownloadable();
    }

    public boolean isRecommended() {
        return status.isDownloadable() && channel.isSupported();
    }

    /**
     * Whether a downloaded file matches the recorded checksum.
     *
     * <p>The check a developer performs before running a binary. Returns false
     * rather than throwing: a mismatched checksum is a question ("is my download
     * truncated, or is something wrong?"), not an exception.
     */
    public boolean verify(byte[] downloaded) {
        return ArtifactChecksum.matches(checksum, ArtifactChecksum.of(downloaded));
    }

    /**
     * The expected artefact filename for this target.
     *
     * <p>Derived rather than stored, so it cannot drift from the target triple
     * and architecture. A developer downloads by name, and a filename that does
     * not match the checksum they were given is unresolvable.
     */
    public String artefactFileName() {
        return "pesaguard-cli-" + version + "-" + platform.targetTriple() + "-"
                + architecture.filenameSegment() + platform.fileExtension();
    }

    /**
     * Withdraws the build.
     *
     * <p>Per-artefact: one platform can be withdrawn for a platform-specific
     * problem without withdrawing the whole release.
     */
    public CliRelease withdraw() {
        if (status == ReleaseStatus.WITHDRAWN) {
            throw new IllegalStateException("This build is already withdrawn");
        }
        return new CliRelease(id, version, platform, architecture, channel,
                ReleaseStatus.WITHDRAWN, checksum, downloadUrl, releaseNotes, publishedAt);
    }
}