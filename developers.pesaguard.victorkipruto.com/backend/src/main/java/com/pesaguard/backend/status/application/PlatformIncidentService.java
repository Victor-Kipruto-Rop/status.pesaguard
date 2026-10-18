package com.pesaguard.backend.status.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;
import com.pesaguard.backend.status.domain.IncidentSeverity;
import com.pesaguard.backend.status.domain.IncidentStatus;
import com.pesaguard.backend.status.domain.PlatformIncident;
import com.pesaguard.backend.status.domain.PlatformIncidentUpdate;
import com.pesaguard.backend.status.infrastructure.PlatformIncidentRepository;
import com.pesaguard.backend.status.infrastructure.PlatformIncidentUpdateRepository;

/** The authoritative incident lifecycle; public views are filtered at the query boundary. */
@Service
public class PlatformIncidentService {
    private final PlatformIncidentRepository incidents;
    private final PlatformIncidentUpdateRepository updates;
    private final Clock clock;

    public PlatformIncidentService(PlatformIncidentRepository incidents, PlatformIncidentUpdateRepository updates, Clock clock) {
        this.incidents = incidents;
        this.updates = updates;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PlatformIncident> publicIncidents() { return incidents.findByPublicVisibleTrueOrderByStartedAtDesc(); }

    @Transactional(readOnly = true)
    public List<PlatformIncident> publicActiveIncidents() {
        return incidents.findByPublicVisibleTrueAndStatusNotOrderByStartedAtDesc(IncidentStatus.RESOLVED);
    }

    @Transactional(readOnly = true)
    public List<PlatformIncidentUpdate> publicUpdates(UUID incidentId) {
        incidents.findByIdAndPublicVisibleTrue(incidentId).orElseThrow(() -> new ResourceNotFoundException("Incident"));
        return updates.findByIncidentIdAndPublicVisibleTrueOrderByCreatedAtAsc(incidentId);
    }

    @Transactional(readOnly = true)
    public List<PlatformIncident> allIncidents() { return incidents.findAll(); }

    @Transactional
    public PlatformIncident create(IncidentCommand command, AuthenticatedOperator operator) {
        String reason = operator.requireReason();
        validate(command);
        PlatformIncident incident = incidents.saveAndFlush(PlatformIncident.open(command.title(), command.severity(), command.status(),
                command.affectedServices(), command.summary(), command.publicVisible(), clock.instant()));
        updates.save(PlatformIncidentUpdate.record(incident.getId(), "Incident created: " + command.summary(),
                command.publicVisible(), operator.operatorId(), operator.subject(), reason));
        return incident;
    }

    @Transactional
    public PlatformIncident update(UUID incidentId, IncidentCommand command, AuthenticatedOperator operator) {
        String reason = operator.requireReason();
        validate(command);
        PlatformIncident incident = incidents.findById(incidentId).orElseThrow(() -> new ResourceNotFoundException("Incident"));
        incident.update(command.severity(), command.status(), command.affectedServices(), command.summary(), command.publicVisible(), clock.instant());
        incidents.saveAndFlush(incident);
        updates.save(PlatformIncidentUpdate.record(incident.getId(), command.summary(), command.publicVisible(),
                operator.operatorId(), operator.subject(), reason));
        return incident;
    }

    @Transactional
    public void addUpdate(UUID incidentId, String message, boolean publicVisible, AuthenticatedOperator operator) {
        String reason = operator.requireReason();
        if (message == null || message.isBlank() || message.trim().length() > 8_000) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_INCIDENT_UPDATE", "An incident update must contain up to 8,000 characters.");
        }
        if (!incidents.existsById(incidentId)) throw new ResourceNotFoundException("Incident");
        updates.save(PlatformIncidentUpdate.record(incidentId, message, publicVisible, operator.operatorId(), operator.subject(), reason));
    }

    @Transactional(readOnly = true)
    public List<PlatformIncidentUpdate> allUpdates(UUID incidentId) {
        if (!incidents.existsById(incidentId)) throw new ResourceNotFoundException("Incident");
        return updates.findByIncidentIdOrderByCreatedAtAsc(incidentId);
    }

    private static void validate(IncidentCommand command) {
        if (command == null || command.title() == null || command.title().isBlank() || command.title().trim().length() > 180
                || command.severity() == null || command.status() == null || command.summary() == null || command.summary().isBlank()
                || command.summary().trim().length() > 8_000) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_INCIDENT", "Incident title, severity, status, and summary are required.");
        }
    }

    public record IncidentCommand(String title, IncidentSeverity severity, IncidentStatus status,
            List<String> affectedServices, String summary, boolean publicVisible) { }
}
