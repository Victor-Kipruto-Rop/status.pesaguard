package com.pesaguard.backend.integration.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pesaguard.backend.environment.domain.EnvironmentType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;

class PesaGuardApiClientTest {

    private HttpServer server;
    private PesaGuardApiClient client;
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestId = new AtomicReference<>();
    private int responseCode;
    private String responseBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        client = new PesaGuardApiClient(new ObjectMapper());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsTheKeyToTheAuthContextEndpointAndVerifiesSandbox() throws Exception {
        responseCode = 200;
        responseBody = "{\"data\":{\"environmentTier\":\"sandbox\"}}";

        var testRequestId = UUID.randomUUID();
        var result = client.test(baseUrl(), "pg_test_secret", EnvironmentType.SANDBOX, testRequestId);

        assertTrue(result.success());
        assertEquals("/api/v1/key-data/auth/context", requestPath.get());
        assertEquals("Bearer pg_test_secret", authorization.get());
        assertEquals(testRequestId.toString(), requestId.get());
    }

    @Test
    void rejectsAnEnvironmentMismatch() throws Exception {
        responseCode = 200;
        responseBody = "{\"data\":{\"environmentTier\":\"sandbox\"}}";

        var result = client.test(baseUrl(), "pg_test_secret", EnvironmentType.PRODUCTION, UUID.randomUUID());

        assertFalse(result.success());
        assertEquals("ENVIRONMENT_MISMATCH", result.failureCategory());
    }

    @Test
    void doesNotTreatDifferentNonProductionTiersAsEquivalent() throws Exception {
        responseCode = 200;
        responseBody = "{\"data\":{\"environmentTier\":\"sandbox\"}}";

        var result = client.test(baseUrl(), "pg_test_secret", EnvironmentType.DEVELOPMENT, UUID.randomUUID());

        assertFalse(result.success());
        assertEquals("ENVIRONMENT_MISMATCH", result.failureCategory());
    }

    @Test
    void reportsDeniedContextAccessWhenTheApiRejectsTheRequest() throws Exception {
        responseCode = 403;
        responseBody = "{\"error\":\"forbidden\"}";

        var result = client.test(baseUrl(), "pg_test_secret", EnvironmentType.SANDBOX, UUID.randomUUID());

        assertFalse(result.success());
        assertEquals("CONTEXT_ACCESS_DENIED", result.failureCategory());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseCode, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
