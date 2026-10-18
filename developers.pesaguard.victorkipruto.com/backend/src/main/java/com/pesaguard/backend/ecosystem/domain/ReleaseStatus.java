package com.pesaguard.backend.ecosystem.domain;

/**
 * Lifecycle of a published artefact.
 *
 * <p>Separate from {@link ReleaseChannel}: a stable release is still withdrawn
 * when a security fix lands, and "we withdrew the download" is a different fact
 * from "this was a beta".
 */
public enum ReleaseStatus {

    /** Listed and downloadable. */
    PUBLISHED,

    /**
     * Still present, still downloadable, no longer advertised.
     *
     * <p>Distinct from WITHDRAWN because the artefact must remain resolvable: a
     * pinned build already deployed somewhere must not stop resolving the moment
     * it is deprecated, or upgrading that software becomes impossible.
     */
    DEPRECATED,

    /**
     * Withdrawn. Not downloadable.
     *
     * <p>Only for a release that must be stopped being used at all, such as one
     * with a known vulnerability. A withdrawn artefact is a deliberate act, and
     * the reason is recorded on the release.
     */
    WITHDRAWN;

    public boolean isDownloadable() {
        return this != WITHDRAWN;
    }

    public boolean isAdvertised() {
        return this == PUBLISHED;
    }
}