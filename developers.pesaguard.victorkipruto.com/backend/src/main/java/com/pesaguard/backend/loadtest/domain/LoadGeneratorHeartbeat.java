package com.pesaguard.backend.loadtest.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "load_generator_heartbeats")
public class LoadGeneratorHeartbeat {
    @Id
    private UUID id;

    @Column(name = "generator_id", nullable = false)
    private UUID generatorId;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "current_vus", nullable = false)
    private int currentVus;

    @Column(name = "current_rps", nullable = false)
    private int currentRps;

    @Column(name = "cpu_percent", nullable = false)
    private double cpuPercent;

    @Column(name = "memory_percent", nullable = false)
    private double memoryPercent;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    protected LoadGeneratorHeartbeat() {
    }

    public static LoadGeneratorHeartbeat record(LoadGenerator generator, Instant at) {
        LoadGeneratorHeartbeat heartbeat = new LoadGeneratorHeartbeat();
        heartbeat.id = UUID.randomUUID();
        heartbeat.generatorId = generator.getId();
        heartbeat.status = generator.getStatus().name();
        heartbeat.currentVus = generator.getCurrentVus();
        heartbeat.currentRps = generator.getCurrentRps();
        heartbeat.cpuPercent = generator.getCpuPercent();
        heartbeat.memoryPercent = generator.getMemoryPercent();
        heartbeat.observedAt = at;
        return heartbeat;
    }
}
