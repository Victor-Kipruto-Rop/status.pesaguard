package com.pesaguard.backend.sandbox.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.pesaguard.backend.environment.domain.EnvironmentType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The record of one sandbox test execution.
 *
 * <p>The environment id and type are carried on every row rather than joined in.
 * That is deliberate: if a bug ever caused a production execution to be recorded
 * against a sandbox, the row says so in plain text and the mistake is visible in
 * the data, rather than having to be inferred from a join. The database check
 * constraint {@code environment_type = 'SANDBOX'} makes such a row unwritable.
 *
 * <p>Excerpts, not bodies. A stored execution log is a debugging aid and must not
 * become a second copy of production-shaped financial data sitting in a table
 * nobody thinks to apply retention to.
 */
@Entity
@Table(name = "sandbox_executions")
public class SandboxExecution {

    private static final int MAX_EXCERPT = 4096;

    @Id
    private UUID id;

    @Column(name = "sandbox_id", nullable = false)
    private UUID sandboxId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment_type", nullable = false, length = 24)
    private EnvironmentType environmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private SandboxExecutionKind kind;

    @Column(name = "method", length = 8)
    private String method;

    @Column(name = "path", length = 512)
    private String path;

    @Column(name = "status_code")
    private Integer statusCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 24)
    private SandboxExecutionOutcome outcome;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "response_excerpt", columnDefinition = "text")
    private String responseExcerpt;

    @Column(name = "request_excerpt", columnDefinition = "text")
    private String requestExcerpt;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SandboxExecution() {
    }

    private SandboxExecution(SandboxIsolation isolation, SandboxExecutionKind kind, String method,
            String path, Integer statusCode, SandboxExecutionOutcome outcome, Integer durationMs,
            String requestExcerpt, String responseExcerpt, UUID actorUserId, String requestId) {
        this.id = UUID.randomUUID();
        this.sandboxId = isolation.sandboxEnvironmentId();
        this.organizationId = isolation.organizationId();
        this.projectId = isolation.projectId();
        this.environmentId = isolation.sandboxEnvironmentId();
        // Hardcoded rather than read from the environment: the only token that can
        // construct this row is one already proved to be a sandbox, so there is
        // nothing else this could truthfully be.
        this.environmentType = EnvironmentType.SANDBOX;
        this.kind = kind;
        this.method = truncate(method, 8);
        this.path = truncate(path, 512);
        this.statusCode = statusCode;
        this.outcome = outcome;
        this.durationMs = durationMs;
        this.requestExcerpt = truncate(requestExcerpt, MAX_EXCERPT);
        this.responseExcerpt = truncate(responseExcerpt, MAX_EXCERPT);
        this.actorUserId = actorUserId;
        this.requestId = truncate(requestId, 64);
    }

    public static SandboxExecution record(SandboxIsolation isolation, SandboxExecutionKind kind,
            String method, String path, Integer statusCode, SandboxExecutionOutcome outcome,
            Integer durationMs, String requestExcerpt, String responseExcerpt,
            UUID actorUserId, String requestId) {
        return new SandboxExecution(isolation, kind, method, path, statusCode, outcome,
                durationMs, requestExcerpt, responseExcerpt, actorUserId, requestId);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public UUID getId() { return id; }
    public UUID getSandboxId() { return sandboxId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getProjectId() { return projectId; }
    public UUID getEnvironmentId() { return environmentId; }
    public EnvironmentType getEnvironmentType() { return environmentType; }
    public SandboxExecutionKind getKind() { return kind; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public Integer getStatusCode() { return statusCode; }
    public SandboxExecutionOutcome getOutcome() { return outcome; }
    public Integer getDurationMs() { return durationMs; }
    public String getResponseExcerpt() { return responseExcerpt; }
    public String getRequestExcerpt() { return requestExcerpt; }
    public UUID getActorUserId() { return actorUserId; }
    public String getRequestId() { return requestId; }
    public Instant getCreatedAt() { return createdAt; }
}