package com.pesaguard.backend.integration.domain;

public enum IntegrationStatus {
    NOT_CONFIGURED,
    CONFIGURING,
    READY_TO_TEST,
    TESTING,
    CONNECTED,
    DEGRADED,
    FAILED,
    DISABLED
}
