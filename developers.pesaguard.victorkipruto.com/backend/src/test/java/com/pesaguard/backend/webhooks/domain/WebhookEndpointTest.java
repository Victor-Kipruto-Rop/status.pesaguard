package com.pesaguard.backend.webhooks.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class WebhookEndpointTest {

    private static final UUID ENVIRONMENT_ID = UUID.randomUUID();

    @Test
    void endpointRemainsBoundToItsEnvironment() {
        assertEquals(ENVIRONMENT_ID, endpoint().getEnvironmentId());
    }

    @Test
    void updatesConfigurationWithoutChangingEndpointStatusOrSecret() {
        WebhookEndpoint endpoint = endpoint();

        endpoint.updateConfiguration("Payments v2", "https://hooks.example.test/next");

        assertEquals("Payments v2", endpoint.getName());
        assertEquals("https://hooks.example.test/next", endpoint.getUrl());
        assertEquals("encrypted-old-secret", endpoint.getSigningSecretCiphertext());
        assertEquals("ACTIVE", endpoint.getStatus());
    }

    @Test
    void rotatesEncryptedSecretWithoutChangingConfiguration() {
        WebhookEndpoint endpoint = endpoint();

        endpoint.rotateSigningSecret("encrypted-new-secret");

        assertEquals("encrypted-new-secret", endpoint.getSigningSecretCiphertext());
        assertEquals("Payments", endpoint.getName());
        assertEquals("https://hooks.example.test/notify", endpoint.getUrl());
    }

    @Test
    void deletedEndpointCannotBeEditedOrRotated() {
        WebhookEndpoint endpoint = endpoint();
        endpoint.delete();

        assertThrows(IllegalStateException.class,
                () -> endpoint.updateConfiguration("new name", "https://hooks.example.test/new"));
        assertThrows(IllegalStateException.class,
                () -> endpoint.rotateSigningSecret("encrypted-new-secret"));
    }

    private static WebhookEndpoint endpoint() {
        return WebhookEndpoint.create(UUID.randomUUID(), UUID.randomUUID(), ENVIRONMENT_ID,
                "Payments", "https://hooks.example.test/notify", "encrypted-old-secret");
    }
}
