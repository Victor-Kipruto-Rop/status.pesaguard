package com.pesaguard.backend.status.api;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.config.DeveloperRuntimeProperties;
import com.pesaguard.backend.status.application.PlatformIncidentService;
import com.pesaguard.backend.status.domain.PlatformIncident;
import com.pesaguard.backend.status.domain.PlatformIncidentUpdate;

/** Public-safe operational status. It never exposes topology, dependency names, or stack traces. */
@RestController
@RequestMapping("/api/v1/status")
public class PlatformStatusController {
    private static final Logger log = LoggerFactory.getLogger(PlatformStatusController.class);

    private final DeveloperRuntimeProperties runtime;
    private final PlatformIncidentService incidents;
    private final JdbcTemplate jdbcTemplate;

    public PlatformStatusController(DeveloperRuntimeProperties runtime, PlatformIncidentService incidents,
            JdbcTemplate jdbcTemplate) {
        this.runtime = runtime;
        this.incidents = incidents;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    ApiResponse<PlatformStatusView> status() {
        List<IncidentView> active = incidents.publicActiveIncidents().stream().map(this::incidentView).toList();
        boolean databaseAvailable = isDatabaseAvailable();
        boolean maintenance = runtime.isMaintenanceMode();
        String apiStatus = componentStatus("API", databaseAvailable ? "OPERATIONAL" : "DEGRADED",
                active, maintenance);
        String overallStatus = maintenance ? "MAINTENANCE"
                : !databaseAvailable || !active.isEmpty() ? "DEGRADED" : "UNKNOWN";
        return ApiResponse.of(new PlatformStatusView(overallStatus,
                List.of(new ComponentView("API", apiStatus),
                        new ComponentView("DASHBOARD", componentStatus("DASHBOARD", "UNKNOWN", active, maintenance)),
                        new ComponentView("AUTHENTICATION", componentStatus("AUTHENTICATION", "UNKNOWN", active, maintenance)),
                        new ComponentView("WEBHOOKS", componentStatus("WEBHOOKS", "UNKNOWN", active, maintenance))),
                runtime.isMaintenanceMode(), runtime.getMaintenanceMessage(), runtime.getMaintenanceStartsAt(),
                runtime.getEstimatedRecoveryAt(), active));
    }

    private boolean isDatabaseAvailable() {
        try {
            return Integer.valueOf(1).equals(jdbcTemplate.queryForObject("select 1", Integer.class));
        } catch (DataAccessException failure) {
            log.warn("Public status database readiness probe failed type={}",
                    failure.getClass().getSimpleName());
            return false;
        }
    }

    private String componentStatus(String service, String measuredStatus,
            List<IncidentView> active, boolean maintenance) {
        if (maintenance) return "MAINTENANCE";
        boolean incidentAffectsService = active.stream().anyMatch(incident ->
                incident.affectedServices().stream().anyMatch(affected ->
                        affected.equalsIgnoreCase("all")
                                || affected.equalsIgnoreCase(service)));
        return incidentAffectsService ? "DEGRADED" : measuredStatus;
    }

    @GetMapping("/incidents")
    ApiResponse<List<IncidentView>> incidentHistory() {
        return ApiResponse.of(incidents.publicIncidents().stream().map(this::incidentView).toList());
    }

    private IncidentView incidentView(PlatformIncident incident) {
        return new IncidentView(incident.getId(), incident.getTitle(), incident.getSeverity().name(), incident.getStatus().name(),
                incident.getAffectedServices(), incident.getSummary(), incident.getStartedAt(), incident.getResolvedAt(),
                incidents.publicUpdates(incident.getId()).stream().map(this::updateView).toList());
    }

    private IncidentUpdateView updateView(PlatformIncidentUpdate update) {
        return new IncidentUpdateView(update.getId(), update.getMessage(), update.getCreatedAt());
    }

    record PlatformStatusView(String overallStatus, List<ComponentView> components, boolean maintenanceMode,
            String maintenanceMessage, Instant maintenanceStartsAt, Instant estimatedRecoveryAt, List<IncidentView> activeIncidents) { }
    record ComponentView(String service, String status) { }
    record IncidentView(java.util.UUID id, String title, String severity, String status, List<String> affectedServices,
            String summary, Instant startedAt, Instant resolvedAt, List<IncidentUpdateView> updates) { }
    record IncidentUpdateView(java.util.UUID id, String message, Instant createdAt) { }
}
