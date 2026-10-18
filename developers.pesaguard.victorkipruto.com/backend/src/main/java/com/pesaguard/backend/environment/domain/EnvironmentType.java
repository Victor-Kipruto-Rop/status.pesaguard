package com.pesaguard.backend.environment.domain;

/**
 * Environment tiers are ordered. Isolation between tiers is a hard rule, not a
 * naming convention: a credential, configuration value, limit, or API key
 * belongs to exactly one tier and is never readable from another.
 *
 * <p>Promotion is forward-only and single-step; see {@link #next()}.
 */
public enum EnvironmentType {
    DEVELOPMENT,
    SANDBOX,
    STAGING,
    PRODUCTION;

    public int rank() {
        return ordinal();
    }

    /** The next tier up, or empty when already the highest tier. */
    public java.util.Optional<EnvironmentType> next() {
        return rank() >= values().length - 1
                ? java.util.Optional.empty()
                : java.util.Optional.of(values()[rank() + 1]);
    }

    public boolean canPromoteTo(EnvironmentType target) {
        return next().filter(candidate -> candidate == target).isPresent();
    }

    public boolean isProtected() {
        return this == PRODUCTION;
    }
}
