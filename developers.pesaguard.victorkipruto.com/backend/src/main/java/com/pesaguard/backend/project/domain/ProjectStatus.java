package com.pesaguard.backend.project.domain;

public enum ProjectStatus {
    ACTIVE,
    DEACTIVATED,
    ARCHIVED;

    public boolean acceptsChanges() {
        return this == ACTIVE;
    }
}
