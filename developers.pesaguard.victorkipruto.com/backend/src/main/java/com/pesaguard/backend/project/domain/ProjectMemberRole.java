package com.pesaguard.backend.project.domain;

public enum ProjectMemberRole {
    MANAGER,
    DEVELOPER,
    VIEWER;

    public boolean atLeast(ProjectMemberRole required) {
        return rank() >= required.rank();
    }

    private int rank() {
        return switch (this) {
            case MANAGER -> 2;
            case DEVELOPER -> 1;
            case VIEWER -> 0;
        };
    }
}