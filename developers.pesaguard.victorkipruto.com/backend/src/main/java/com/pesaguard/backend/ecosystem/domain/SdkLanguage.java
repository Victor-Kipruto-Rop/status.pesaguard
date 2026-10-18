package com.pesaguard.backend.ecosystem.domain;

/**
 * SDK languages.
 *
 * <p>JavaScript is deliberately absent as a separate entry. A JavaScript SDK and
 * a TypeScript one are the same artefact at runtime and are published from the
 * same source; listing them separately would let a maintainer publish a
 * TypeScript release and forget the JavaScript one, leaving developers on the
 * same major version with two different builds.
 */
public enum SdkLanguage {

    JAVA,
    PYTHON,
    TYPESCRIPT,
    GO,
    PHP,
    DOTNET;

    /**
     * The label shown to developers, which is not always the enum name.
     *
     * <p>{@code DOTNET} is "C#" because that is what developers search for, and
     * the package ecosystem is NuGet for every .NET language.
     */
    public String displayName() {
        return switch (this) {
            case DOTNET -> "C#";
            default -> name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT);
        };
    }

    /**
     * The package ecosystem a release is published to.
     *
     * <p>Recorded per release rather than per language because a language may
     * publish to more than one, and the install command is what a developer
     * actually copies.
     */
    public String defaultPackageName() {
        return switch (this) {
            case JAVA -> "com.pesaguard:pesaguard-java";
            case PYTHON -> "pesaguard";
            case TYPESCRIPT -> "@pesaguard/pesaguard";
            case GO -> "github.com/pesaguard/pesaguard-go";
            case PHP -> "pesaguard/pesaguard";
            case DOTNET -> "PesaGuard.Client";
        };
    }

    /**
     * The minimum runtime this language's SDK can support.
     *
     * <p>Used to reject a release declaring a runtime older than the SDK's own
     * requirement, which would produce a package that cannot run.
     */
    public String minimumRuntime() {
        return switch (this) {
            case JAVA -> "17";
            case PYTHON -> "3.9";
            case TYPESCRIPT -> "5.0";
            case GO -> "1.21";
            case PHP -> "8.1";
            case DOTNET -> "8.0";
        };
    }
}