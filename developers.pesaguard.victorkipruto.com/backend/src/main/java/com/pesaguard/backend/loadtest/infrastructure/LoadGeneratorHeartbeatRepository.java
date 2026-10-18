package com.pesaguard.backend.loadtest.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.loadtest.domain.LoadGeneratorHeartbeat;

public interface LoadGeneratorHeartbeatRepository extends JpaRepository<LoadGeneratorHeartbeat, UUID> {
}
