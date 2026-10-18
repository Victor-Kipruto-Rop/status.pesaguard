package com.pesaguard.backend.environment.domain;

public enum EnvironmentStatus {
    ACTIVE,
    SUSPENDED,
    DEACTIVATED;

    public boolean acceptsChanges() {
        return this == ACTIVE;
    }
}
