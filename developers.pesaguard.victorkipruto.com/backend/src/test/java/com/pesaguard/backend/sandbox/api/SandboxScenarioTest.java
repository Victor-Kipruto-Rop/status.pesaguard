package com.pesaguard.backend.sandbox.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SandboxScenarioTest {

    @Test
    void exposesOnlyFixedScenariosAndSandboxLocalPaths() {
        assertEquals(9, SandboxScenario.values().length);
        for (SandboxScenario scenario : SandboxScenario.values()) {
            assertTrue(scenario.path().startsWith("/"));
            assertFalse(scenario.path().contains("://"));
            assertFalse(scenario.path().contains(".."));
        }
    }

    @Test
    void returnsExpectedPaymentAndFailureResults() {
        assertEquals(201, SandboxScenario.SUCCESSFUL_PAYMENT.statusCode());
        assertTrue(SandboxScenario.SUCCESSFUL_PAYMENT.resultBody(1250, "KES").contains("\"amount\":1250"));
        assertEquals(401, SandboxScenario.INVALID_CREDENTIAL.statusCode());
        assertTrue(SandboxScenario.INVALID_CREDENTIAL.resultBody(1250, "KES")
                .contains("\"code\":\"invalid_credential\""));
        assertEquals(502, SandboxScenario.WEBHOOK_FAILURE.statusCode());
        assertEquals(503, SandboxScenario.NETWORK_FAILURE.statusCode());
        assertEquals(200, SandboxScenario.TRANSACTION_REVERSAL.statusCode());
    }
}
