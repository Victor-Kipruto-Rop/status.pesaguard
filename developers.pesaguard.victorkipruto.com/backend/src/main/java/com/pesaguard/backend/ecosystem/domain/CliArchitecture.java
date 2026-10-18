package com.pesaguard.backend.ecosystem.domain;

/**
 * CPU architectures a CLI build can target.
 */
public enum CliArchitecture {

    X86_64,
    ARM64;

    public String displayName() {
        return this == X86_64 ? "x86_64" : "arm64";
    }

    /**
     * The Go-style architecture token used in artefact filenames.
     */
    public String token() {
        return this == X86_64 ? "amd64" : "arm64";
    }

    /**
     * The artefact segment for this architecture.
     *
     * <p>{@code 386} is spelled out rather than abbreviated because a filename
     * is not the place to be terse, and an ambiguous segment is impossible to
     * debug once it is published.
     */
    public String filenameSegment() {
        return token();
    }
}