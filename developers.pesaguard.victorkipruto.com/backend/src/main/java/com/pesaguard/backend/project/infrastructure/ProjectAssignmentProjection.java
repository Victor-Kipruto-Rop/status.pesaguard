package com.pesaguard.backend.project.infrastructure;

import java.util.UUID;

/** Minimal organization-scoped projection for member assignment summaries. */
public interface ProjectAssignmentProjection {
    UUID getUserId();
    String getProjectName();
}
