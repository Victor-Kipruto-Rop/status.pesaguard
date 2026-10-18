package com.pesaguard.backend.ecosystem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Version parsing and ordering.
 *
 * <p>These exist because string comparison gets version ordering wrong in both
 * directions, and this registry's job is to tell a developer which version to
 * install. A wrong answer here is a support incident, not a cosmetic error.
 */
class SemanticVersionTest {

    private SemanticVersion version(String value) {
        return SemanticVersion.parse(value).orElseThrow();
    }

    @Test
    void plainVersionsParse() {
        assertThat(version("1.2.3").major()).isEqualTo(1);
        assertThat(version("1.2.3").minor()).isEqualTo(2);
        assertThat(version("1.2.3").patch()).isEqualTo(3);
        assertThat(version("1.2.3").isPreRelease()).isFalse();
    }

    @Test
    void aLeadingVIsAccepted() {
        // Every ecosystem tags with a v prefix; rejecting it would be pedantry
        // that makes the registry annoying rather than safer.
        assertThat(version("v2.0.0").major()).isEqualTo(2);
    }

    @Test
    void prereleasesParse() {
        SemanticVersion rc = version("1.0.0-rc.1");

        assertThat(rc.isPreRelease()).isTrue();
        assertThat(rc.preRelease()).isEqualTo("rc.1");
    }

    @Test
    void malformedVersionsAreRejected() {
        assertThat(SemanticVersion.parse("1.2")).isEmpty();
        assertThat(SemanticVersion.parse("1.2.3.4")).isEmpty();
        assertThat(SemanticVersion.parse("01.2.3")).isEmpty();
        assertThat(SemanticVersion.parse("abc")).isEmpty();
        assertThat(SemanticVersion.parse("")).isEmpty();
        assertThat(SemanticVersion.parse(null)).isEmpty();
    }

    @Test
    void minorIsComparedNumericallyNotLexically() {
        // The bug this class exists to prevent: "1.10.0" sorts before "1.9.0"
        // as a string, so a naive registry recommends an older SDK.
        assertThat(version("1.10.0")).isGreaterThan(version("1.9.0"));
    }

    @Test
    void prereleaseSortsBelowItsOwnRelease() {
        assertThat(version("1.0.0-rc.1")).isLessThan(version("1.0.0"));
    }

    @Test
    void numericPrereleaseIdentifiersCompareNumerically() {
        // rc.10 is the later release. Lexical comparison gets this backwards
        // because "10" sorts before "2", which is exactly the bug this guards.
        assertThat(version("1.0.0-rc.10")).isGreaterThan(version("1.0.0-rc.2"));
    }

    @Test
    void alphanumericPrereleaseOutranksNumeric() {
        // Semver rule: numeric identifiers always have lower precedence.
        assertThat(version("1.0.0-rc.9")).isLessThan(version("1.0.0-rc.beta"));
    }

    @Test
    void aLongerPrereleaseIsGreaterWhenPrefixesMatch() {
        assertThat(version("1.0.0-rc")).isLessThan(version("1.0.0-rc.1"));
    }

    @Test
    void sortingPicksTheNewest() {
        List<SemanticVersion> sorted = List.of(version("1.9.0"), version("1.10.0"),
                version("1.10.1-rc.1"), version("1.10.1"))
                .stream().sorted(SemanticVersion.NEWEST_FIRST).toList();

        assertThat(sorted.get(0)).isEqualTo(version("1.10.1"));
        assertThat(sorted.get(1)).isEqualTo(version("1.10.1-rc.1"));
        assertThat(sorted.get(2)).isEqualTo(version("1.10.0"));
    }

    @Test
    void roundTripsToString() {
        assertThat(version("1.2.3").toString()).isEqualTo("1.2.3");
        assertThat(version("1.2.3-rc.1").toString()).isEqualTo("1.2.3-rc.1");
    }
}
