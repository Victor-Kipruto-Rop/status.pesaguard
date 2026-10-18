package com.pesaguard.backend.status.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.platformadmin.domain.OperatorCapability;
import com.pesaguard.backend.status.application.PlatformIncidentService;
import com.pesaguard.backend.status.application.PlatformIncidentService.IncidentCommand;
import com.pesaguard.backend.status.domain.IncidentSeverity;
import com.pesaguard.backend.status.domain.IncidentStatus;
import com.pesaguard.backend.status.domain.PlatformIncident;
import com.pesaguard.backend.status.domain.PlatformIncidentUpdate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Operator-only incident lifecycle. A token capability and a stated reason are both required on writes. */
@RestController
@RequestMapping("/internal/configuration/incidents")
public class OperatorIncidentController {
    private final PlatformIncidentService service;

    public OperatorIncidentController(PlatformIncidentService service) { this.service = service; }

    @GetMapping
    ApiResponse<List<OperatorIncidentView>> list(@AuthenticationPrincipal AuthenticatedOperator operator) {
        operator.require(OperatorCapability.PLATFORM_CONFIG_READ);
        return ApiResponse.of(service.allIncidents().stream().map(this::view).toList());
    }

    @GetMapping("/{incidentId}/updates")
    ApiResponse<List<OperatorIncidentUpdateView>> updates(@AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID incidentId) {
        operator.require(OperatorCapability.PLATFORM_CONFIG_READ);
        return ApiResponse.of(service.allUpdates(incidentId).stream().map(update -> new OperatorIncidentUpdateView(
                update.getId(), update.getMessage(), update.isPublicVisible(), update.getCreatedAt())).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<OperatorIncidentView> create(@AuthenticationPrincipal AuthenticatedOperator operator,
            @jakarta.validation.Valid @RequestBody IncidentRequest request) {
        operator.require(OperatorCapability.PLATFORM_CONFIG_WRITE);
        return ApiResponse.of(view(service.create(request.command(), operator)));
    }

    @PutMapping("/{incidentId}")
    ApiResponse<OperatorIncidentView> update(@AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID incidentId, @jakarta.validation.Valid @RequestBody IncidentRequest request) {
        operator.require(OperatorCapability.PLATFORM_CONFIG_WRITE);
        return ApiResponse.of(view(service.update(incidentId, request.command(), operator)));
    }

    @PostMapping("/{incidentId}/updates")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void addUpdate(@AuthenticationPrincipal AuthenticatedOperator operator, @PathVariable UUID incidentId,
            @jakarta.validation.Valid @RequestBody IncidentUpdateRequest request) {
        operator.require(OperatorCapability.PLATFORM_CONFIG_WRITE);
        service.addUpdate(incidentId, request.message(), request.publicVisible(), operator);
    }

    private OperatorIncidentView view(PlatformIncident incident) {
        return new OperatorIncidentView(incident.getId(), incident.getTitle(), incident.getSeverity().name(),
                incident.getStatus().name(), incident.getAffectedServices(), incident.getSummary(), incident.isPublicVisible(),
                incident.getStartedAt(), incident.getResolvedAt());
    }

    record IncidentRequest(@NotBlank @Size(max = 180) String title, @NotNull IncidentSeverity severity,
            @NotNull IncidentStatus status, @NotEmpty @Size(max = 12) List<@NotBlank @Size(max = 80) String> affectedServices,
            @NotBlank @Size(max = 8000) String summary, boolean publicVisible) {
        IncidentCommand command() { return new IncidentCommand(title, severity, status, affectedServices, summary, publicVisible); }
    }
    record IncidentUpdateRequest(@NotBlank @Size(max = 8000) String message, boolean publicVisible) { }
    record OperatorIncidentView(UUID id, String title, String severity, String status, List<String> affectedServices,
            String summary, boolean publicVisible, java.time.Instant startedAt, java.time.Instant resolvedAt) { }
    record OperatorIncidentUpdateView(UUID id, String message, boolean publicVisible, java.time.Instant createdAt) { }
}
