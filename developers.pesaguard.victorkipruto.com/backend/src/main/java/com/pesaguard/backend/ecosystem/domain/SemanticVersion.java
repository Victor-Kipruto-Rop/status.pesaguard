package com.pesaguard.backend.ecosystem.domain;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A semantic version, parsed rather than string-compared.
 *
 * <p>Version strings are compared as strings far too often, and it is wrong in
 * both directions: {@code "1.10.0" < "1.9.0"} lexicographically, and
 * {@code "1.0.0-rc1"} above {@code "1.0.0"} by any plain comparison. Both
 * produce the wrong "latest release" recommendation to a developer, which is
 * the entire purpose of this registry.
 */
public record SemanticVersion(int major, int minor, int patch, String preRelease)
        implements Comparable<SemanticVersion> {

    private static final Pattern PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-([0-9A-Za-z.-]+))?$");

    private static final int MAX_PART = 1_000_000;

    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version parts cannot be negative");
        }
        if (major > MAX_PART || minor > MAX_PART || patch > MAX_PART) {
            throw new IllegalArgumentException("Version part is implausibly large");
        }
    }

    /**
     * Parses a strict semantic version.
     *
     * <p>Strict on leading zeros, because {@code 01.2.3} is ambiguous and no
     * release tool should be emitting it. A leading {@code v} is accepted since
     * every ecosystem's tag convention uses one and rejecting it would make the
     * registry annoying rather than safer.
     */
    public static Optional<SemanticVersion> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String candidate = value.trim();
        if (candidate.startsWith("v") || candidate.startsWith("V")) {
            candidate = candidate.substring(1);
        }
        var matcher = PATTERN.matcher(candidate);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SemanticVersion(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    matcher.group(4)));
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    /**
     * Whether this is a pre-release.
     *
     * <p>A pre-release is never "the latest" for a developer who did not opt in.
     */
    public boolean isPreRelease() {
        return preRelease != null;
    }

    /** The major version, the compatibility boundary. */
    public int majorVersion() {
        return major;
    }

    /**
     * Compatibility ordering.
     *
     * <p>Precedence follows semver: a pre-release sorts <b>below</b> its own
     * release, and pre-release identifiers compare numerically where numeric and
     * lexically otherwise.
     */
    @Override
    public int compareTo(SemanticVersion other) {
        int majorComparison = Integer.compare(major, other.major);
        if (majorComparison != 0) {
            return majorComparison;
        }
        int minorComparison = Integer.compare(minor, other.minor);
        if (minorComparison != 0) {
            return minorComparison;
        }
        int patchComparison = Integer.compare(patch, other.patch);
        if (patchComparison != 0) {
            return patchComparison;
        }
        return comparePreRelease(preRelease, other.preRelease);
    }

    private static int comparePreRelease(String left, String right) {
        if (left == null && right == null) {
            return 0;
        }
        // A release outranks any pre-release of the same numbers.
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int shared = Math.min(leftParts.length, rightParts.length);
        for (int index = 0; index < shared; index++) {
            int comparison = compareIdentifier(leftParts[index], rightParts[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) {
            return Long.compare(Long.parseLong(left), Long.parseLong(right));
        }
        // Numeric identifiers always have lower precedence than alphanumeric.
        if (leftNumeric) {
            return -1;
        }
        if (rightNumeric) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /** Ordering for picking the newest release. */
    public static final Comparator<SemanticVersion> NEWEST_FIRST =
            Comparator.reverseOrder();

    @Override
    public String toString() {
        return preRelease == null
                ? major + "." + minor + "." + patch
                : major + "." + minor + "." + patch + "-" + preRelease;
    }
}