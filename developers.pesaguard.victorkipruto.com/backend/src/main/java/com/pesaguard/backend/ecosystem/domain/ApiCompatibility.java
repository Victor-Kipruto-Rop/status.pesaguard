package com.pesaguard.backend.ecosystem.domain;

/**
 * The range of platform API versions an SDK release works against.
 *
 * <p>Recorded per release rather than assumed from the major version. A patch
 * release of an SDK can narrow or widen support, and assuming otherwise would let
 * the registry recommend an SDK against an API it cannot actually talk to.
 */
public record ApiCompatibility(int minimumApiMajor, int maximumApiMajor) {

    /**
     * Validates the range.
     *
     * <p>Only the major version is tracked. Compatibility is a property of a
     * breaking change, and a platform that broke its API inside a major version
     * would be the platform breaking its own contract.
     */
    public ApiCompatibility {
        if (minimumApiMajor < 1) {
            throw new IllegalArgumentException("minimumApiMajor must be at least 1");
        }
        if (maximumApiMajor < minimumApiMajor) {
            throw new IllegalArgumentException(
                    "maximumApiMajor must not be below minimumApiMajor");
        }
        if (maximumApiMajor - minimumApiMajor > MAX_SUPPORTED_RANGE) {
            throw new IllegalArgumentException(
                    "An SDK may not claim compatibility with more than "
                            + MAX_SUPPORTED_RANGE + " API majors");
        }
    }

    /**
     * How many majors one SDK may claim.
     *
     * <p>Bounded because a wide range is nearly always a metadata mistake. An SDK
     * claiming to work with everything is not evidence, and a developer who finds
     * out at integration time blames the platform.
     */
    static final int MAX_SUPPORTED_RANGE = 5;

    public static ApiCompatibility of(int minimumApiMajor, int maximumApiMajor) {
        return new ApiCompatibility(minimumApiMajor, maximumApiMajor);
    }

    /**
     * Whether this SDK works against a given platform API major.
     *
     * <p>Inclusive at both ends: an SDK declaring support through API v1 works
     * against v1.
     */
    public boolean supports(int apiMajor) {
        return apiMajor >= minimumApiMajor && apiMajor <= maximumApiMajor;
    }

    /**
     * Whether two compatibility ranges overlap.
     *
     * <p>Used to warn when a platform major is retired while SDKs still claim to
     * support it.
     */
    public boolean overlaps(ApiCompatibility other) {
        return minimumApiMajor <= other.maximumApiMajor
                && other.minimumApiMajor <= maximumApiMajor;
    }

    @Override
    public String toString() {
        return minimumApiMajor == maximumApiMajor
                ? "v" + minimumApiMajor
                : "v" + minimumApiMajor + "-v" + maximumApiMajor;
    }
}