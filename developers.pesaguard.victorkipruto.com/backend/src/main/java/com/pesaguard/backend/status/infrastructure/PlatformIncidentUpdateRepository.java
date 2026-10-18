package com.pesaguard.backend.status.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.pesaguard.backend.status.domain.PlatformIncidentUpdate;

public interface PlatformIncidentUpdateRepository extends JpaRepository<PlatformIncidentUpdate, UUID> {
    List<PlatformIncidentUpdate> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);
    List<PlatformIncidentUpdate> findByIncidentIdAndPublicVisibleTrueOrderByCreatedAtAsc(UUID incidentId);
}
