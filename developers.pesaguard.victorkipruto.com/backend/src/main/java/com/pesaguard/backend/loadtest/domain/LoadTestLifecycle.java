package com.pesaguard.backend.loadtest.domain;

public enum LoadTestLifecycle {
    DRAFT, READY, QUEUED, PREPARING, RUNNING, PAUSED, STOPPING,
    COMPLETED, FAILED, CANCELLED, TIMEOUT
}
