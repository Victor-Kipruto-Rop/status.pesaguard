package com.pesaguard.backend.loadtest.api;

import java.time.Instant;
import java.util.UUID;

import com.pesaguard.backend.loadtest.domain.GeneratorStatus;
import com.pesaguard.backend.loadtest.domain.LoadGenerator;

public record LoadGeneratorView(
        UUID generatorId,
        String name,
        String region,
        GeneratorStatus status,
        int maxVus,
        int maxRps,
        int currentVus,
        int currentRps,
        int availableVus,
        int availableRps,
        double cpuPercent,
        double memoryPercent,
        String version,
        String executorType,
        Instant lastHeartbeatAt) {
    public static LoadGeneratorView from(LoadGenerator generator, boolean heartbeatFresh) {
        GeneratorStatus status = heartbeatFresh ? generator.getStatus() : GeneratorStatus.OFFLINE;
        return new LoadGeneratorView(generator.getId(), generator.getName(), generator.getRegion(),
                status, generator.getMaxVus(), generator.getMaxRps(), generator.getCurrentVus(),
                generator.getCurrentRps(), heartbeatFresh ? generator.getAvailableVus() : 0,
                heartbeatFresh ? generator.getAvailableRps() : 0, generator.getCpuPercent(),
                generator.getMemoryPercent(), generator.getVersion(), generator.getExecutorType(),
                generator.getLastHeartbeatAt());
    }
}
