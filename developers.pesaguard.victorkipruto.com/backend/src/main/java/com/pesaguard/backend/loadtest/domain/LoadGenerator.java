package com.pesaguard.backend.loadtest.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "load_generators")
public class LoadGenerator {
    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 80)
    private String region;

    @Column(name = "max_vus", nullable = false)
    private int maxVus;

    @Column(name = "max_rps", nullable = false)
    private int maxRps;

    @Column(name = "current_vus", nullable = false)
    private int currentVus;

    @Column(name = "current_rps", nullable = false)
    private int currentRps;

    @Column(name = "cpu_percent", nullable = false)
    private double cpuPercent;

    @Column(name = "memory_percent", nullable = false)
    private double memoryPercent;

    @Column(nullable = false, length = 40)
    private String version;

    @Column(name = "executor_type", nullable = false, length = 20)
    private String executorType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private GeneratorStatus status;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Version
    @Column(name = "version_number", nullable = false)
    private long entityVersion;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoadGenerator() {
    }

    public static LoadGenerator register(UUID id, String name, String region,
            int maxVus, int maxRps, String version, String executorType, Instant now) {
        LoadGenerator generator = new LoadGenerator();
        generator.id = id;
        generator.name = name;
        generator.region = region;
        generator.maxVus = maxVus;
        generator.maxRps = maxRps;
        generator.version = version;
        generator.executorType = executorType;
        generator.status = GeneratorStatus.AVAILABLE;
        generator.lastHeartbeatAt = now;
        return generator;
    }

    public void heartbeat(GeneratorStatus reportedStatus, int currentVus, int currentRps,
            double cpuPercent, double memoryPercent, Instant now) {
        this.currentVus = currentVus;
        this.currentRps = currentRps;
        this.cpuPercent = cpuPercent;
        this.memoryPercent = memoryPercent;
        this.status = reportedStatus;
        this.lastHeartbeatAt = now;
    }

    public void updateRegistration(String name, String region, int maxVus, int maxRps,
            String version, String executorType, Instant now) {
        this.name = name;
        this.region = region;
        this.maxVus = maxVus;
        this.maxRps = maxRps;
        this.version = version;
        this.executorType = executorType;
        this.lastHeartbeatAt = now;
    }

    public int availableVus() {
        return status == GeneratorStatus.AVAILABLE
                ? Math.max(0, maxVus - currentVus)
                : 0;
    }

    public int availableRps() {
        return status == GeneratorStatus.AVAILABLE
                ? Math.max(0, maxRps - currentRps)
                : 0;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getRegion() { return region; }
    public int getMaxVus() { return maxVus; }
    public int getMaxRps() { return maxRps; }
    public int getCurrentVus() { return currentVus; }
    public int getCurrentRps() { return currentRps; }
    public double getCpuPercent() { return cpuPercent; }
    public double getMemoryPercent() { return memoryPercent; }
    public String getVersion() { return version; }
    public String getExecutorType() { return executorType; }
    public GeneratorStatus getStatus() { return status; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public int getAvailableVus() { return availableVus(); }
    public int getAvailableRps() { return availableRps(); }
}
