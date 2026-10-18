package com.pesaguard.backend.loadtest.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "load_test_generator_allocations")
public class LoadTestGeneratorAllocation {
    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "generator_id", nullable = false)
    private UUID generatorId;

    @Column(name = "allocated_vus", nullable = false)
    private int allocatedVus;

    @Column(name = "allocated_rps", nullable = false)
    private int allocatedRps;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    protected LoadTestGeneratorAllocation() {
    }

    public static LoadTestGeneratorAllocation create(UUID runId, UUID generatorId, int vus, int rps) {
        LoadTestGeneratorAllocation item = new LoadTestGeneratorAllocation();
        item.id = UUID.randomUUID();
        item.runId = runId;
        item.generatorId = generatorId;
        item.allocatedVus = vus;
        item.allocatedRps = rps;
        item.status = "ALLOCATED";
        return item;
    }

    public void claim(Instant now) {
        if (!"ALLOCATED".equals(status)) throw new IllegalStateException("Allocation is not available");
        status = "CLAIMED";
        claimedAt = now;
    }

    public void release() {
        status = "RELEASED";
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public UUID getGeneratorId() { return generatorId; }
    public int getAllocatedVus() { return allocatedVus; }
    public int getAllocatedRps() { return allocatedRps; }
    public String getStatus() { return status; }
}
