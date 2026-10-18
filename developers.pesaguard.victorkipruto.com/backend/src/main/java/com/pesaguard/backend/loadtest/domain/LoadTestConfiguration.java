package com.pesaguard.backend.loadtest.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "load_tests")
public class LoadTestConfiguration {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "endpoint_path", nullable = false, length = 1024)
    private String endpointPath;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> headers;

    @Column(name = "request_body", columnDefinition = "text")
    private String requestBody;

    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "load_pattern", nullable = false, length = 20)
    private LoadPattern loadPattern;

    @Column(name = "target_vus", nullable = false)
    private int targetVus;

    @Column(name = "target_rps")
    private Integer targetRps;

    @Column(name = "maximum_rps")
    private Integer maximumRps;

    @Column(name = "maximum_duration_seconds", nullable = false)
    private int maximumDurationSeconds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<LoadTestStage> stages;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<LoadTestThreshold> thresholds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private LoadTestLifecycle lifecycle;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoadTestConfiguration() {
    }

    public static LoadTestConfiguration create(UUID organizationId, UUID projectId, UUID environmentId,
            UUID createdBy, String name, String description, String endpointPath, String httpMethod,
            String contentType, Map<String, String> headers, String requestBody, UUID apiKeyId,
            LoadPattern loadPattern, int targetVus, Integer targetRps, Integer maximumRps,
            int maximumDurationSeconds, List<LoadTestStage> stages, List<LoadTestThreshold> thresholds) {
        LoadTestConfiguration test = new LoadTestConfiguration();
        test.id = UUID.randomUUID();
        test.organizationId = organizationId;
        test.projectId = projectId;
        test.environmentId = environmentId;
        test.createdBy = createdBy;
        test.name = name.trim();
        test.description = description == null || description.isBlank() ? null : description.trim();
        test.endpointPath = endpointPath;
        test.httpMethod = httpMethod;
        test.contentType = contentType;
        test.headers = Map.copyOf(headers);
        test.requestBody = requestBody;
        test.apiKeyId = apiKeyId;
        test.loadPattern = loadPattern;
        test.targetVus = targetVus;
        test.targetRps = targetRps;
        test.maximumRps = maximumRps;
        test.maximumDurationSeconds = maximumDurationSeconds;
        test.stages = List.copyOf(stages);
        test.thresholds = List.copyOf(thresholds);
        test.lifecycle = LoadTestLifecycle.READY;
        return test;
    }

    public void update(String name, String description, String endpointPath, String httpMethod,
            String contentType, Map<String, String> headers, String requestBody, UUID apiKeyId,
            LoadPattern loadPattern, int targetVus, Integer targetRps, Integer maximumRps,
            int maximumDurationSeconds, List<LoadTestStage> stages, List<LoadTestThreshold> thresholds) {
        this.name = name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.endpointPath = endpointPath;
        this.httpMethod = httpMethod;
        this.contentType = contentType;
        this.headers = Map.copyOf(headers);
        this.requestBody = requestBody;
        this.apiKeyId = apiKeyId;
        this.loadPattern = loadPattern;
        this.targetVus = targetVus;
        this.targetRps = targetRps;
        this.maximumRps = maximumRps;
        this.maximumDurationSeconds = maximumDurationSeconds;
        this.stages = List.copyOf(stages);
        this.thresholds = List.copyOf(thresholds);
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public UUID getCreatedBy() { return createdBy; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getEndpointPath() { return endpointPath; }
    public String getHttpMethod() { return httpMethod; }
    public String getContentType() { return contentType; }
    public Map<String, String> getHeaders() { return headers; }
    public String getRequestBody() { return requestBody; }
    public UUID getApiKeyId() { return apiKeyId; }
    public LoadPattern getLoadPattern() { return loadPattern; }
    public int getTargetVus() { return targetVus; }
    public Integer getTargetRps() { return targetRps; }
    public Integer getMaximumRps() { return maximumRps; }
    public int getMaximumDurationSeconds() { return maximumDurationSeconds; }
    public List<LoadTestStage> getStages() { return stages; }
    public List<LoadTestThreshold> getThresholds() { return thresholds; }
    public LoadTestLifecycle getLifecycle() { return lifecycle; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
