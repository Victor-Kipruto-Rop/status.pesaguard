package com.pesaguard.backend.status.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
@Table(name = "platform_incidents")
public class PlatformIncident {
    @Id private UUID id;
    @Column(nullable = false, length = 180) private String title;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private IncidentSeverity severity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private IncidentStatus status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "affected_services", nullable = false, columnDefinition = "jsonb")
    private List<String> affectedServices;
    @Column(nullable = false, columnDefinition = "text") private String summary;
    @Column(name = "public_visible", nullable = false) private boolean publicVisible;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;

    protected PlatformIncident() { }

    public static PlatformIncident open(String title, IncidentSeverity severity, IncidentStatus status,
            List<String> affectedServices, String summary, boolean publicVisible, Instant startedAt) {
        PlatformIncident incident = new PlatformIncident();
        incident.id = UUID.randomUUID();
        incident.title = title.trim();
        incident.severity = severity;
        incident.status = status;
        incident.affectedServices = normaliseServices(affectedServices);
        incident.summary = summary.trim();
        incident.publicVisible = publicVisible;
        incident.startedAt = startedAt;
        incident.resolvedAt = status == IncidentStatus.RESOLVED ? startedAt : null;
        return incident;
    }

    public void update(IncidentSeverity severity, IncidentStatus status, List<String> affectedServices,
            String summary, boolean publicVisible, Instant now) {
        this.severity = severity;
        this.status = status;
        this.affectedServices = normaliseServices(affectedServices);
        this.summary = summary.trim();
        this.publicVisible = publicVisible;
        this.resolvedAt = status == IncidentStatus.RESOLVED ? now : null;
    }

    private static List<String> normaliseServices(List<String> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("At least one affected service is required.");
        List<String> normalised = values.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim()).distinct().toList();
        if (normalised.isEmpty() || normalised.size() > 12 || normalised.stream().anyMatch(value -> value.length() > 80)) {
            throw new IllegalArgumentException("Affected services must contain 1 to 12 names of at most 80 characters.");
        }
        return new ArrayList<>(normalised);
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public IncidentSeverity getSeverity() { return severity; }
    public IncidentStatus getStatus() { return status; }
    public List<String> getAffectedServices() { return List.copyOf(affectedServices); }
    public String getSummary() { return summary; }
    public boolean isPublicVisible() { return publicVisible; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
