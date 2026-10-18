package com.pesaguard.backend.ecosystem.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CliReleaseRepository extends JpaRepository<CliReleaseEntity, UUID> {
    List<CliReleaseEntity> findAllByOrderByPublishedAtDesc();
}
