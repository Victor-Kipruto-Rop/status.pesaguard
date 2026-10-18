package com.pesaguard.backend.ecosystem.domain;

/**
 * Which track a release belongs to.
 *
 * <p>Ordering matters and is declared here rather than derived from name
 * ordering: {@code ALPHA < BETA < STABLE} alphabetically is also correct, but
 * relying on that would break silently the day someone added a channel.
 */
public enum ReleaseChannel {

    /** Early preview. Explicit opt-in, no stability promise. */
    ALPHA(0, false),

    /** Feature-complete preview. Still opt-in. */
    BETA(1, false),

    /** The supported track. */
    STABLE(2, true),

    /** Pre-release builds of the next major, for early adopters. */
    NEXT(1, false);

    private final int stabilityRank;
    private final boolean supported;

    ReleaseChannel(int stabilityRank, boolean supported) {
        this.stabilityRank = stabilityRank;
        this.supported = supported;
    }

    /** Whether builds on this channel are supported for production use. */
    public boolean isSupported() {
        return supported;
    }

    /**
     * Whether a developer should be pointed here by default.
     *
     * <p>Only STABLE. An unqualified "latest" must never resolve to a preview.
     */
    public boolean isDefault() {
        return this == STABLE;
    }

    /**
     * Whether a channel is ordered above another.
     *
     * <p>Used to pick the best available release, and deliberately strict: two
     * equal ranks are not ordered, so a caller cannot treat BETA and NEXT as
     * interchangeable when choosing what to recommend.
     */
    public boolean isMoreStableThan(ReleaseChannel other) {
        return stabilityRank > other.stabilityRank;
    }
}