package com.pesaguard.backend.ecosystem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Checksum handling.
 *
 * <p>The registry's integrity guarantee rests entirely on this. A wrong checksum
 * is worse than none: a developer who cannot tell a truncated download from a
 * tampered one has no way to know what they just installed.
 */
class ArtifactChecksumTest {

    private static final String VALID =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void aValidDigestIsAccepted() {
        assertThat(ArtifactChecksum.isValid(VALID)).isTrue();
    }

    @Test
    void uppercaseIsAcceptedBecauseToolsDiffer() {
        // Every checksum tool does not agree on case; rejecting a correct uppercase
        // digest would reject a correct artefact.
        assertThat(ArtifactChecksum.isValid(VALID.toUpperCase(java.util.Locale.ROOT))).isTrue();
    }

    @Test
    void malformedDigestsAreRejected() {
        assertThat(ArtifactChecksum.isValid(null)).isFalse();
        assertThat(ArtifactChecksum.isValid("")).isFalse();
        // Wrong length: 63 and 65 characters.
        assertThat(ArtifactChecksum.isValid(VALID.substring(0, 63))).isFalse();
        assertThat(ArtifactChecksum.isValid(VALID + "a")).isFalse();
        // Non-hex characters.
        assertThat(ArtifactChecksum.isValid("z".repeat(64))).isFalse();
    }

    @Test
    void normalisingRejectsAMalformedDigest() {
        assertThatThrownBy(() -> ArtifactChecksum.normalise("not-a-digest"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalisingLowercases() {
        assertThat(ArtifactChecksum.normalise(VALID.toUpperCase(java.util.Locale.ROOT)))
                .isEqualTo(VALID);
    }

    @Test
    void computesTheDigestOfKnownContent() {
        // The SHA-256 of the empty string, a fixed published vector.
        assertThat(ArtifactChecksum.of(new byte[0])).isEqualTo(VALID);
    }

    @Test
    void theDigestIsStableAndContentDependent() {
        byte[] first = "pesaguard".getBytes(StandardCharsets.UTF_8);
        byte[] second = "pesaguar".getBytes(StandardCharsets.UTF_8);

        assertThat(ArtifactChecksum.of(first)).isEqualTo(ArtifactChecksum.of(first));
        assertThat(ArtifactChecksum.of(first)).isNotEqualTo(ArtifactChecksum.of(second));
    }

    @Test
    void matchingDetectsATamperedDownload() {
        String actual = ArtifactChecksum.of("genuine".getBytes(StandardCharsets.UTF_8));
        String tampered = ArtifactChecksum.of("tampered".getBytes(StandardCharsets.UTF_8));

        assertThat(ArtifactChecksum.matches(actual, actual)).isTrue();
        assertThat(ArtifactChecksum.matches(actual, tampered)).isFalse();
    }

    @Test
    void matchingIsCaseInsensitive() {
        String actual = ArtifactChecksum.of("genuine".getBytes(StandardCharsets.UTF_8));

        assertThat(ArtifactChecksum.matches(actual, actual.toUpperCase(java.util.Locale.ROOT)))
                .isTrue();
    }

    @Test
    void anInvalidCandidateNeverMatches() {
        // A malformed value must not be treated as a match by any path.
        assertThat(ArtifactChecksum.matches(VALID, "short")).isFalse();
        assertThat(ArtifactChecksum.matches(VALID, null)).isFalse();
    }

    @Test
    void aReleasedBuildRefusesToExistWithoutAValidChecksum() {
        // Construction-time validation: an artefact with an unverifiable checksum
        // must never enter the registry at all.
        assertThatThrownBy(() -> SdkRelease.publish(java.util.UUID.randomUUID(),
                SdkLanguage.JAVA, SemanticVersion.parse("1.0.0").orElseThrow(),
                ReleaseChannel.STABLE, ApiCompatibility.of(1, 1), "short",
                "https://docs.example", "notes", java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBuildVerifiesADownloadedFile() {
        byte[] binary = "pesaguard-cli-binary".getBytes(StandardCharsets.UTF_8);
        CliRelease build = CliRelease.publish(java.util.UUID.randomUUID(),
                SemanticVersion.parse("1.0.0").orElseThrow(), CliPlatform.LINUX,
                CliArchitecture.X86_64, ReleaseChannel.STABLE, ArtifactChecksum.of(binary),
                "https://downloads.example/cli", "initial", java.time.Instant.now());

        assertThat(build.verify(binary)).isTrue();
        assertThat(build.verify("other".getBytes(StandardCharsets.UTF_8))).isFalse();
    }
}