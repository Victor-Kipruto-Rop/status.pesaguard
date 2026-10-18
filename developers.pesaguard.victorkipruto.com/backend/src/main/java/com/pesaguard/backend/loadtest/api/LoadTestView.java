package com.pesaguard.backend.loadtest.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pesaguard.backend.environment.api.EnvironmentApiBaseUrls;
import com.pesaguard.backend.environment.domain.ProjectEnvironment;
import com.pesaguard.backend.loadtest.domain.LoadTestConfiguration;
import com.pesaguard.backend.loadtest.domain.LoadTestLifecycle;
import com.pesaguard.backend.loadtest.domain.LoadPattern;
import com.pesaguard.backend.loadtest.domain.LoadTestStage;
import com.pesaguard.backend.loadtest.domain.LoadTestThreshold;

public record LoadTestView(
        UUID id,
        UUID projectId,
        UUID environmentId,
        String name,
        String description,
        String targetUrl,
        String endpointPath,
        String httpMethod,
        String contentType,
        Map<String, String> headers,
        String requestBody,
        UUID apiKeyId,
        LoadPattern loadPattern,
        int targetVus,
        Integer targetRps,
        Integer maximumRps,
        int maximumDurationSeconds,
        List<LoadTestStage> stages,
        List<LoadTestThreshold> thresholds,
        LoadTestLifecycle lifecycle,
        Instant createdAt,
        Instant updatedAt) {

    public static LoadTestView from(LoadTestConfiguration test, ProjectEnvironment environment) {
        return new LoadTestView(test.getId(), test.getProjectId(), test.getEnvironmentId(),
                test.getName(), test.getDescription(),
                EnvironmentApiBaseUrls.forType(environment.getType()) + test.getEndpointPath(),
                test.getEndpointPath(), test.getHttpMethod(), test.getContentType(),
                test.getHeaders(), test.getRequestBody(), test.getApiKeyId(), test.getLoadPattern(),
                test.getTargetVus(), test.getTargetRps(), test.getMaximumRps(),
                test.getMaximumDurationSeconds(), test.getStages(), test.getThresholds(),
                test.getLifecycle(), test.getCreatedAt(), test.getUpdatedAt());
    }
}
