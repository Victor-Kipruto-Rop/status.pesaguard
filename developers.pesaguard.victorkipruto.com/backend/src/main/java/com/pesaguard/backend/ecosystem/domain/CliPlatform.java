package com.pesaguard.backend.ecosystem.domain;

/**
 * A platform a CLI build can target.
 */
public enum CliPlatform {

    WINDOWS,
    LINUX,
    MACOS;

    public String displayName() {
        return switch (this) {
            case MACOS -> "macOS";
            default -> name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT);
        };
    }

    /**
     * The Go-style target triple used in artefact filenames.
     *
     * <p>Exposed because the download filename is what a developer verifies a
     * checksum against, and it has to match what the release pipeline produced
     * exactly.
     */
    public String targetTriple() {
        return switch (this) {
            case WINDOWS -> "windows";
            case LINUX -> "linux";
            case MACOS -> "darwin";
        };
    }

    /**
     * The file extension of a build for this platform.
     *
     * <p>Windows builds carry {@code .exe} because that is what executes; the
     * others are extensionless and made executable by permission bits.
     */
    public String fileExtension() {
        return this == WINDOWS ? ".exe" : "";
    }
}