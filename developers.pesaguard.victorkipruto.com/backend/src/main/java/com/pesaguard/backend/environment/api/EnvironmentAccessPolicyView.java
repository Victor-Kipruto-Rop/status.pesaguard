package com.pesaguard.backend.environment.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.pesaguard.backend.environment.domain.EnvironmentAccessPolicy;
import com.pesaguard.backend.environment.domain.EnvironmentAccessSubjectType;
import com.pesaguard.backend.environment.domain.EnvironmentPermission;

public record EnvironmentAccessPolicyView(
        UUID id,
        UUID environmentId,
        EnvironmentAccessSubjectType subjectType,
        String subjectRole,
        Set<EnvironmentPermission> permissions,
        Set<String> ipAllowlist,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static EnvironmentAccessPolicyView from(EnvironmentAccessPolicy policy) {
        return new EnvironmentAccessPolicyView(policy.getId(), policy.getEnvironmentId(),
                policy.getSubjectType(), policy.getSubjectRole(), policy.getPermissions(),
                policy.getIpAllowlist(), policy.getCreatedBy(), policy.getCreatedAt(), policy.getUpdatedAt());
    }
}
