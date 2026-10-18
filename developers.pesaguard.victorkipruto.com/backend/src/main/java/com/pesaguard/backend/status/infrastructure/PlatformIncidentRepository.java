package com.pesaguard.backend.status.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.pesaguard.backend.status.domain.IncidentStatus;
import com.pesaguard.backend.status.domain.PlatformIncident;

public interface PlatformIncidentRepository extends JpaRepository<PlatformIncident, UUID> {
    List<PlatformIncident> findByPublicVisibleTrueOrderByStartedAtDesc();
    List<PlatformIncident> findByPublicVisibleTrueAndStatusNotOrderByStartedAtDesc(IncidentStatus status);
    Optional<PlatformIncident> findByIdAndPublicVisibleTrue(UUID id);
}
