package com.pesaguard.backend.credentials.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import com.pesaguard.backend.common.api.CorrelationIdFilter;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.config.PipelineServiceJwtProperties;
import com.pesaguard.backend.security.servicejwt.PipelineServiceJwtService;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PipelineApiKeySynchronizerTest {

    @Test
    void syncsActiveKeyWhenIntegrationTargetIsConfigured() {
        String endpoint = System.getenv("PESAGUARD_PIPELINE_SYNC_TEST_URL");
        String secret = System.getenv("PESAGUARD_PIPELINE_SYNC_TEST_SECRET");
        Assumptions.assumeTrue(endpoint != null && !endpoint.isBlank()
                && secret != null && !secret.isBlank());

        ApiKey key = ApiKey.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "pipeline-sync-smoke-test",
                "pgk_test_prefix",
                "a".repeat(64),
                "read:analytics",
                Instant.parse("2027-01-01T00:00:00.123456789Z"),
                UUID.randomUUID());
        key.activate();

        var synchronizer = new PipelineApiKeySynchronizer(
                JsonMapper.builder().build(),
                endpoint,
                secret,
                true);
        String keyHash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        synchronizer.synchronize(key, keyHash);
    }

    @Test
    void sendsValidJsonAndSurfacesOnlySafePipelineErrorCodes() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedContentLength = new AtomicReference<>();
        AtomicReference<String> receivedTimestamp = new AtomicReference<>();
        AtomicReference<String> receivedSignature = new AtomicReference<>();
        AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        AtomicReference<String> receivedIdempotencyKey = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/developer-api-keys/sync", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedContentLength.set(exchange.getRequestHeaders().getFirst("Content-length"));
            receivedTimestamp.set(exchange.getRequestHeaders().getFirst("X-PesaGuard-Timestamp"));
            receivedSignature.set(exchange.getRequestHeaders().getFirst("X-PesaGuard-Signature"));
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] response = """
                    {"error":"invalid_sync_payload","reason":"invalid_source_version"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            UUID organizationId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            UUID environmentId = UUID.randomUUID();
            ApiKey key = ApiKey.create(
                    organizationId,
                    projectId,
                    environmentId,
                    "test",
                    "pgk_test_prefix",
                    "a".repeat(64),
                    "read:analytics",
                    Instant.parse("2027-01-01T00:00:00.123456789Z"),
                    UUID.randomUUID());
            key.activate();
            var objectMapper = JsonMapper.builder().build();
            var synchronizer = new PipelineApiKeySynchronizer(
                    objectMapper,
                    "http://127.0.0.1:" + server.getAddress().getPort()
                            + "/internal/v1/developer-api-keys/sync",
                    "test-bridge-secret-value-32-bytes",
                    true);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> synchronizer.synchronize(key, "b".repeat(64)));

            var payload = objectMapper.readTree(receivedBody.get());
            assertTrue(payload.isObject());
            assertEquals(
                    Integer.toString(receivedBody.get().getBytes(StandardCharsets.UTF_8).length),
                    receivedContentLength.get());
            assertEquals(key.getId().toString(), payload.path("key_id").asString());
            assertEquals(
                    com.pesaguard.backend.tenancy.DeveloperTenantId.derive(
                            organizationId.toString(), projectId.toString(), environmentId.toString()),
                    payload.path("tenant_id").asString());
            assertEquals("ACTIVE", payload.path("status").asString());
            assertEquals("2027-01-01T00:00:00.123456789Z", payload.path("expires_at").asString());
            assertEquals(
                    hmac("test-bridge-secret-value-32-bytes",
                            receivedTimestamp.get() + "\nPOST\n/internal/v1/developer-api-keys/sync\n"
                                    + receivedBody.get()),
                    receivedSignature.get());
            assertEquals(null, receivedAuthorization.get());
            assertTrue(receivedIdempotencyKey.get().startsWith("pgs_"));
            assertTrue(exception.getMessage().contains("invalid_sync_payload:invalid_source_version"));
            assertFalse(exception.getMessage().contains("b".repeat(64)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsDualJwtAndHmacOrJwtPrimaryWithOptionalHmacFallback() throws Exception {
        assertSyncAuthHeaders("DUAL_REQUIRED", "test-bridge-secret-value-32-bytes", true);
        assertSyncAuthHeaders("JWT_PRIMARY", "", false);
        assertSyncAuthHeaders("JWT_PRIMARY", "test-bridge-secret-value-32-bytes", true);
    }

    @Test
    void dualRequiredModeRejectsMissingHmacConfiguration() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        PipelineServiceJwtProperties properties = new PipelineServiceJwtProperties();
        properties.setMode("DUAL_REQUIRED");
        properties.setActiveKid("sync-test-key");
        properties.setPrivateKeyPem(pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        PipelineServiceJwtService jwtService = new PipelineServiceJwtService(
                properties, java.time.Clock.systemUTC());

        assertThrows(IllegalStateException.class, () -> new PipelineApiKeySynchronizer(
                JsonMapper.builder().build(),
                "https://core.example.test/internal/v1/developer-api-keys/sync",
                "",
                true,
                jwtService));
    }

    @Test
    void productionProfileRejectsDevelopmentHttpBridgesForSyncAndLifecycle() {
        ApiKey key = activeTestKey();
        for (String host : List.of(
                "localhost",
                "127.0.0.1",
                "dashboard_api",
                "host.docker.internal")) {
            int port = host.equals("dashboard_api") || host.equals("host.docker.internal") ? 5001 : 8080;
            var synchronizer = testSynchronizer(
                    "http://" + host + ":" + port + "/internal/v1/developer-api-keys/sync",
                    true);

            BusinessException syncFailure = assertThrows(
                    BusinessException.class, () -> synchronizer.synchronize(key, "b".repeat(64)));
            BusinessException lifecycleFailure = assertThrows(
                    BusinessException.class, () -> synchronizer.revoke(key));

            assertTrue(syncFailure.getMessage().contains("must use HTTPS"), host);
            assertTrue(lifecycleFailure.getMessage().contains("must use HTTPS"), host);
        }
    }

    @Test
    void sendsIdempotentLifecycleRequestWithExactJwtScopeAndHmacSignature() throws Exception {
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedIdempotencyKey = new AtomicReference<>();
        AtomicReference<String> receivedTimestamp = new AtomicReference<>();
        AtomicReference<String> receivedSignature = new AtomicReference<>();
        AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/keys", exchange -> {
            receivedPath.set(exchange.getRequestURI().getRawPath());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            receivedTimestamp.set(exchange.getRequestHeaders().getFirst("X-PesaGuard-Timestamp"));
            receivedSignature.set(exchange.getRequestHeaders().getFirst("X-PesaGuard-Signature"));
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"status\":\"suspended\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            PipelineServiceJwtProperties properties = new PipelineServiceJwtProperties();
            properties.setMode("DUAL_REQUIRED");
            properties.setActiveKid("lifecycle-test-key");
            properties.setPrivateKeyPem(pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
            PipelineServiceJwtService jwtService = new PipelineServiceJwtService(
                    properties, java.time.Clock.systemUTC());

            var synchronizer = new PipelineApiKeySynchronizer(
                    JsonMapper.builder().build(),
                    "http://127.0.0.1:" + server.getAddress().getPort()
                            + "/internal/v1/developer-api-keys/sync",
                    "test-bridge-secret-value-32-bytes",
                    true,
                    jwtService);
            UUID organizationId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            UUID environmentId = UUID.randomUUID();
            ApiKey key = ApiKey.create(
                    organizationId,
                    projectId,
                    environmentId,
                    "lifecycle-test",
                    "pgk_lifecycle_test",
                    "a".repeat(64),
                    "read:analytics",
                    Instant.parse("2027-01-01T00:00:00Z"),
                    UUID.randomUUID());
            key.activate();
            key.suspend(Instant.parse("2026-10-07T12:00:00Z"));
            org.springframework.test.util.ReflectionTestUtils.setField(key, "version", 2L);

            synchronizer.suspend(key);
            String firstIdempotencyKey = receivedIdempotencyKey.get();
            synchronizer.suspend(key);

            assertEquals("/internal/v1/keys/" + key.getId() + "/suspend", receivedPath.get());
            var payload = JsonMapper.builder().build().readTree(receivedBody.get());
            assertEquals(organizationId.toString(), payload.path("organization_id").asString());
            assertEquals(projectId.toString(), payload.path("project_id").asString());
            assertEquals(environmentId.toString(), payload.path("environment_id").asString());
            assertEquals(
                    com.pesaguard.backend.tenancy.DeveloperTenantId.derive(
                            organizationId.toString(), projectId.toString(), environmentId.toString()),
                    payload.path("tenant_id").asString());
            assertEquals(2, payload.path("source_version").asInt());
            assertTrue(firstIdempotencyKey.startsWith("pgl_"));
            assertEquals(firstIdempotencyKey, receivedIdempotencyKey.get());

            SignedJWT token = SignedJWT.parse(
                    receivedAuthorization.get().substring("Bearer ".length()));
            assertEquals("service:key:suspend",
                    token.getJWTClaimsSet().getStringListClaim("scope").getFirst());
            assertEquals(hmac(
                    "test-bridge-secret-value-32-bytes",
                    receivedTimestamp.get() + "\nPOST\n" + receivedPath.get() + "\n" + receivedBody.get()),
                    receivedSignature.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void propagatesRequestContextOnSyncAndLifecycleCallsAndReturnsItFromCore() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String correlationId = "corr:api-key-lifecycle-17";
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        AtomicReference<Map<String, String>> syncHeaders = new AtomicReference<>();
        AtomicReference<Map<String, String>> lifecycleHeaders = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/developer-api-keys/sync", exchange -> {
            exchange.getRequestBody().readAllBytes();
            syncHeaders.set(Map.of(
                    "X-Request-ID", exchange.getRequestHeaders().getFirst("X-Request-ID"),
                    "X-Correlation-ID", exchange.getRequestHeaders().getFirst("X-Correlation-ID"),
                    "traceparent", exchange.getRequestHeaders().getFirst("traceparent")));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/internal/v1/keys", exchange -> {
            exchange.getRequestBody().readAllBytes();
            lifecycleHeaders.set(Map.of(
                    "X-Request-ID", exchange.getRequestHeaders().getFirst("X-Request-ID"),
                    "X-Correlation-ID", exchange.getRequestHeaders().getFirst("X-Correlation-ID"),
                    "traceparent", exchange.getRequestHeaders().getFirst("traceparent")));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            ApiKey key = activeTestKey();
            key.suspend(Instant.parse("2026-10-07T12:00:00Z"));
            org.springframework.test.util.ReflectionTestUtils.setField(key, "version", 2L);
            var synchronizer = new PipelineApiKeySynchronizer(
                    JsonMapper.builder().build(),
                    "http://127.0.0.1:" + server.getAddress().getPort()
                            + "/internal/v1/developer-api-keys/sync",
                    "test-bridge-secret-value-32-bytes",
                    true);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Request-ID", requestId);
            request.addHeader("X-Correlation-ID", correlationId);
            request.addHeader("traceparent", traceparent);
            MockHttpServletResponse response = new MockHttpServletResponse();

            new CorrelationIdFilter().doFilter(request, response, (servletRequest, servletResponse) -> {
                synchronizer.synchronize(key, "b".repeat(64));
                synchronizer.suspend(key);
            });

            Map<String, String> expected = Map.of(
                    "X-Request-ID", requestId,
                    "X-Correlation-ID", correlationId,
                    "traceparent", traceparent);
            assertEquals(expected, syncHeaders.get());
            assertEquals(expected, lifecycleHeaders.get());
            assertEquals(requestId, response.getHeader("X-Request-ID"));
            assertEquals(correlationId, response.getHeader("X-Correlation-ID"));
            assertEquals(traceparent, response.getHeader("traceparent"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsStableIdempotencyKeyForRepeatedSyncOfSameVersion() throws Exception {
        AtomicReference<String> idempotencyKey = new AtomicReference<>();
        AtomicReference<String> firstKey = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/developer-api-keys/sync", exchange -> {
            String current = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            firstKey.compareAndSet(null, current);
            idempotencyKey.set(current);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            ApiKey key = ApiKey.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "sync-idempotency-test",
                    "pgk_sync_idem_test",
                    "a".repeat(64),
                    "read:analytics",
                    Instant.parse("2027-01-01T00:00:00Z"),
                    UUID.randomUUID());
            key.activate();
            var synchronizer = new PipelineApiKeySynchronizer(
                    JsonMapper.builder().build(),
                    "http://127.0.0.1:" + server.getAddress().getPort()
                            + "/internal/v1/developer-api-keys/sync",
                    "test-bridge-secret-value-32-bytes",
                    true);

            synchronizer.synchronize(key, "b".repeat(64));
            synchronizer.synchronize(key, "b".repeat(64));

            assertTrue(firstKey.get().startsWith("pgs_"));
            assertEquals(firstKey.get(), idempotencyKey.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesOnlyTransientStatusesAndTransportFailuresAsRetryable() {
        for (int status : List.of(400, 401, 403, 409, 501, 505)) {
            assertFalse(PipelineApiKeySynchronizer.isRetryableStatus(status), "status " + status);
        }
        for (int status : List.of(408, 429, 500, 502, 503, 504)) {
            assertTrue(PipelineApiKeySynchronizer.isRetryableStatus(status), "status " + status);
        }
        assertTrue(PipelineApiKeySynchronizer.isRetryableTransportFailure(new java.io.IOException()));
        assertFalse(PipelineApiKeySynchronizer.isRetryableTransportFailure(
                new javax.net.ssl.SSLException("TLS handshake failed")));
    }

    @Test
    void retries503WithExponentialJitterAndStopsAtStrictAttemptCap() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> initialIdempotencyKey = new AtomicReference<>();
        List<Duration> delays = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/developer-api-keys/sync", exchange -> {
            exchange.getRequestBody().readAllBytes();
            initialIdempotencyKey.compareAndSet(
                    null, exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var synchronizer = testSynchronizer(server, delays::add, () -> 1.0, new Semaphore(16));

            assertThrows(BusinessException.class, () -> synchronizer.synchronize(activeTestKey(), "b".repeat(64)));

            assertEquals(3, requests.get());
            assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200)), delays);
            assertTrue(initialIdempotencyKey.get().startsWith("pgs_"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotRetryPermanentStatuses() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger nextStatus = new AtomicInteger(400);
        List<Duration> delays = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/developer-api-keys/sync", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            exchange.sendResponseHeaders(nextStatus.getAndAdd(1), -1);
            exchange.close();
        });
        server.start();
        try {
            var synchronizer = testSynchronizer(server, delays::add, () -> 1.0, new Semaphore(16));

            for (int status : List.of(400, 401, 403, 409)) {
                assertThrows(
                        BusinessException.class,
                        () -> synchronizer.synchronize(activeTestKey(), "b".repeat(64)));
            }

            assertEquals(4, requests.get());
            assertTrue(delays.isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRequestsWhenBulkheadIsFullWithoutCallingThePipeline() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/developer-api-keys/sync", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            var synchronizer = testSynchronizer(server, ignored -> {
            }, () -> 1.0, new Semaphore(0));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> synchronizer.synchronize(activeTestKey(), "b".repeat(64)));

            assertEquals(0, requests.get());
            assertTrue(exception.getMessage().contains("unavailable"));
        } finally {
            server.stop(0);
        }
    }

    private static PipelineApiKeySynchronizer testSynchronizer(
            HttpServer server,
            PipelineApiKeySynchronizer.RetrySleeper sleeper,
            java.util.function.DoubleSupplier jitter,
            Semaphore bulkhead) {
        return testSynchronizer(
                "http://127.0.0.1:" + server.getAddress().getPort()
                        + "/internal/v1/developer-api-keys/sync",
                sleeper,
                jitter,
                bulkhead,
                false);
    }

    private static PipelineApiKeySynchronizer testSynchronizer(String endpoint, boolean productionProfileActive) {
        return testSynchronizer(
                endpoint,
                ignored -> {
                },
                () -> 1.0,
                new Semaphore(16),
                productionProfileActive);
    }

    private static PipelineApiKeySynchronizer testSynchronizer(
            String endpoint,
            PipelineApiKeySynchronizer.RetrySleeper sleeper,
            java.util.function.DoubleSupplier jitter,
            Semaphore bulkhead,
            boolean productionProfileActive) {
        return new PipelineApiKeySynchronizer(
                JsonMapper.builder().build(),
                endpoint,
                "test-bridge-secret-value-32-bytes",
                true,
                null,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                sleeper,
                jitter,
                bulkhead,
                productionProfileActive);
    }

    private static ApiKey activeTestKey() {
        ApiKey key = ApiKey.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "sync-resilience-test",
                "pgk_sync_resilience",
                "a".repeat(64),
                "read:analytics",
                Instant.parse("2027-01-01T00:00:00Z"),
                UUID.randomUUID());
        key.activate();
        return key;
    }

    private static void assertSyncAuthHeaders(String mode, String secret, boolean expectHmac) throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> timestampHeader = new AtomicReference<>();
        AtomicReference<String> signatureHeader = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/developer-api-keys/sync", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            timestampHeader.set(exchange.getRequestHeaders().getFirst("X-PesaGuard-Timestamp"));
            signatureHeader.set(exchange.getRequestHeaders().getFirst("X-PesaGuard-Signature"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            PipelineServiceJwtProperties properties = new PipelineServiceJwtProperties();
            properties.setMode(mode);
            properties.setActiveKid("sync-test-key");
            properties.setPrivateKeyPem(pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
            PipelineServiceJwtService jwtService = new PipelineServiceJwtService(
                    properties, java.time.Clock.systemUTC());

            var synchronizer = new PipelineApiKeySynchronizer(
                    JsonMapper.builder().build(),
                    "http://127.0.0.1:" + server.getAddress().getPort()
                            + "/internal/v1/developer-api-keys/sync",
                    secret,
                    true,
                    jwtService);
            ApiKey key = ApiKey.create(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test",
                    "pgk_sync_test", "a".repeat(64), "read:analytics",
                    Instant.parse("2027-01-01T00:00:00Z"), UUID.randomUUID());
            key.activate();

            synchronizer.synchronize(key, "b".repeat(64));

            assertTrue(authorization.get().startsWith("Bearer "));
            SignedJWT token = SignedJWT.parse(authorization.get().substring("Bearer ".length()));
            assertEquals("sync-test-key", token.getHeader().getKeyID());
            assertEquals("developer-platform", token.getJWTClaimsSet().getIssuer());
            assertEquals("core-api", token.getJWTClaimsSet().getAudience().getFirst());
            assertEquals("svc-developer-platform", token.getJWTClaimsSet().getSubject());
            assertEquals("service:sync", token.getJWTClaimsSet().getStringListClaim("scope").getFirst());
            assertEquals(expectHmac, signatureHeader.get() != null);
            assertEquals(expectHmac, timestampHeader.get() != null);
            if (expectHmac) {
                assertEquals(hmac(secret,
                        timestampHeader.get() + "\nPOST\n/internal/v1/developer-api-keys/sync\n" + body.get()),
                        signatureHeader.get());
            }
        } finally {
            server.stop(0);
        }
    }

    private static String hmac(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded)
                + "\n-----END " + label + "-----";
    }

}
