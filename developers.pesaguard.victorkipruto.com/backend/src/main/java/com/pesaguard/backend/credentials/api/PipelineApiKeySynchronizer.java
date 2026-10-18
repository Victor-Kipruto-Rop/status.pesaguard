package com.pesaguard.backend.credentials.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.security.servicejwt.PipelineServiceJwtMode;
import com.pesaguard.backend.security.servicejwt.PipelineServiceJwtService;
import com.pesaguard.backend.tenancy.DeveloperTenantId;

/**
 * Sends API-key lifecycle snapshots to the pipeline without transmitting a key
 * secret. The shared HMAC is exclusively for authenticating this service channel.
 */
@Component
public class PipelineApiKeySynchronizer {

    private static final String SYNC_PATH = "/internal/v1/developer-api-keys/sync";
    private static final String LIFECYCLE_PATH = "/internal/v1/keys/";
    private static final int MAX_RESPONSE_BODY_BYTES = 4096;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_CONCURRENT_REQUESTS = 16;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RETRY_BASE_DELAY = Duration.ofMillis(100);
    private static final Duration RETRY_MAX_DELAY = Duration.ofSeconds(1);
    private static final Semaphore SYNC_BULKHEAD = new Semaphore(MAX_CONCURRENT_REQUESTS);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final System.Logger LOGGER = System.getLogger(PipelineApiKeySynchronizer.class.getName());
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String secret;
    private final boolean required;
    private final boolean productionProfileActive;
    private final PipelineServiceJwtService serviceJwtService;
    private final HttpClient httpClient;
    private final RetrySleeper retrySleeper;
    private final DoubleSupplier jitter;
    private final Semaphore bulkhead;

    public PipelineApiKeySynchronizer(
            ObjectMapper objectMapper,
            @Value("${pesaguard.pipeline.key-sync-url:}") String endpoint,
            @Value("${pesaguard.pipeline.key-sync-secret:}") String secret,
            @Value("${pesaguard.pipeline.key-sync-required:false}") boolean required) {
        this(objectMapper, endpoint, secret, required, null, false);
    }

    @Autowired
    public PipelineApiKeySynchronizer(
            ObjectMapper objectMapper,
            @Value("${pesaguard.pipeline.key-sync-url:}") String endpoint,
            @Value("${pesaguard.pipeline.key-sync-secret:}") String secret,
            @Value("${pesaguard.pipeline.key-sync-required:false}") boolean required,
            PipelineServiceJwtService serviceJwtService,
            Environment environment) {
        this(objectMapper, endpoint, secret, required, serviceJwtService, HTTP,
                PipelineApiKeySynchronizer::sleep, () -> ThreadLocalRandom.current().nextDouble(),
                SYNC_BULKHEAD, environment.acceptsProfiles(Profiles.of("production")));
    }

    PipelineApiKeySynchronizer(
            ObjectMapper objectMapper,
            String endpoint,
            String secret,
            boolean required,
            PipelineServiceJwtService serviceJwtService) {
        this(objectMapper, endpoint, secret, required, serviceJwtService, false);
    }

    private PipelineApiKeySynchronizer(
            ObjectMapper objectMapper,
            String endpoint,
            String secret,
            boolean required,
            PipelineServiceJwtService serviceJwtService,
            boolean productionProfileActive) {
        this(objectMapper, endpoint, secret, required, serviceJwtService, HTTP,
                PipelineApiKeySynchronizer::sleep, () -> ThreadLocalRandom.current().nextDouble(),
                SYNC_BULKHEAD, productionProfileActive);
    }

    PipelineApiKeySynchronizer(
            ObjectMapper objectMapper,
            String endpoint,
            String secret,
            boolean required,
            PipelineServiceJwtService serviceJwtService,
            HttpClient httpClient,
            RetrySleeper retrySleeper,
            DoubleSupplier jitter,
            Semaphore bulkhead) {
        this(objectMapper, endpoint, secret, required, serviceJwtService, httpClient,
                retrySleeper, jitter, bulkhead, false);
    }

    PipelineApiKeySynchronizer(
            ObjectMapper objectMapper,
            String endpoint,
            String secret,
            boolean required,
            PipelineServiceJwtService serviceJwtService,
            HttpClient httpClient,
            RetrySleeper retrySleeper,
            DoubleSupplier jitter,
            Semaphore bulkhead,
            boolean productionProfileActive) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.secret = secret;
        this.required = required;
        this.productionProfileActive = productionProfileActive;
        this.serviceJwtService = serviceJwtService;
        this.httpClient = httpClient;
        this.retrySleeper = retrySleeper;
        this.jitter = jitter;
        this.bulkhead = bulkhead;
        if (serviceJwtService != null && serviceJwtService.mode().jwtEnabled()
                && ((serviceJwtService.mode().hmacRequired()
                        && (secret == null || secret.isBlank()))
                        || (secret != null && !secret.isBlank()
                                && secret.getBytes(StandardCharsets.UTF_8).length < 32))) {
            throw new IllegalStateException("Pipeline service JWT mode has unusable HMAC configuration.");
        }
    }

    public void synchronize(ApiKey key, String keyHash) {
        PipelineServiceJwtMode mode = serviceJwtService == null
                ? PipelineServiceJwtMode.HMAC_ONLY
                : serviceJwtService.mode();
        boolean useHmac = !mode.jwtEnabled() || mode.hmacRequired()
                || (secret != null && !secret.isBlank());
        if (mode == PipelineServiceJwtMode.HMAC_ONLY) {
            if (endpoint == null || endpoint.isBlank() || secret == null || secret.isBlank()) {
                if (!required) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "API-key sync is not configured; key {0} will not authenticate in the pipeline.",
                            key.getId());
                    return;
                }
                throw synchronizationUnavailable(
                        "API-key synchronization is not configured; no API key was created.");
            }
        } else if (endpoint == null || endpoint.isBlank()) {
            if (!required) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "API-key sync is not configured; key {0} will not authenticate in the pipeline.",
                        key.getId());
                return;
            }
            throw synchronizationUnavailable(
                    "API-key synchronization is not configured; no API key was created.");
        }
        if ((useHmac && (secret == null || secret.isBlank()))
                || (secret != null && !secret.isBlank()
                        && secret.getBytes(StandardCharsets.UTF_8).length < 32)) {
            throw synchronizationUnavailable(
                    "API-key synchronization is misconfigured; no API key was created.");
        }

        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw synchronizationUnavailable(
                    "API-key synchronization is misconfigured; no API key was created.");
        }
        if (!isSecureOrDevelopmentBridge(uri)) {
            throw synchronizationUnavailable(
                    "API-key synchronization must use HTTPS outside the local development bridge.");
        }
        if (!SYNC_PATH.equals(uri.getRawPath()) || uri.getQuery() != null || uri.getFragment() != null) {
            throw synchronizationUnavailable(
                    "API-key synchronization is misconfigured; no API key was created.");
        }

        SyncPayload payload = new SyncPayload(
                key.getId().toString(),
                scopedTenantId(key),
                key.getOrganizationId().toString(),
                key.getProjectId().toString(),
                key.getEnvironmentId().toString(),
                key.getVersion(),
                keyHash,
                key.getKeyPrefix(),
                key.scopeSet().stream().sorted().toList(),
                ScopeCodec.decode(key.getIpAllowlist()).stream().sorted().toList(),
                key.getStatus().name(),
                key.getExpiresAt() == null ? null : key.getExpiresAt().toString());
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw synchronizationUnavailable(
                    "API-key synchronization failed; no API key was created.", exception);
        }
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", syncIdempotencyKey(key))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        addRequestContextHeaders(requestBuilder);
        if (mode.jwtEnabled()) {
            try {
                requestBuilder.header("Authorization", "Bearer " + serviceJwtService.issueSyncToken());
            } catch (RuntimeException exception) {
                throw synchronizationUnavailable(
                        "API-key synchronization service authentication is unavailable; no API key was created.");
            }
        }
        if (useHmac) {
            String canonical = timestamp + "\nPOST\n" + SYNC_PATH + "\n" + body;
            String signature = sign(secret, canonical);
            requestBuilder.header("X-PesaGuard-Timestamp", timestamp)
                    .header("X-PesaGuard-Signature", signature);
        }
        HttpRequest request = requestBuilder.build();
        try {
            PipelineResponse response = sendWithRetries(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String details = pipelineErrorDetails(response.body());
                throw synchronizationUnavailable(
                        "The API-key pipeline rejected synchronization (HTTP " + response.statusCode()
                                + (details == null ? "" : ", " + details)
                                + "); no API key was created.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw synchronizationUnavailable(
                    "API-key synchronization was interrupted; no API key was created.", exception);
        } catch (java.io.IOException exception) {
            throw synchronizationUnavailable(
                    "The API-key pipeline is unavailable; no API key was created. Please retry shortly.",
                    exception);
        }
    }

    public void revoke(ApiKey key) {
        sendLifecycleRequest(key, "revoke");
    }

    public void suspend(ApiKey key) {
        sendLifecycleRequest(key, "suspend");
    }

    private void sendLifecycleRequest(ApiKey key, String operation) {
        PipelineServiceJwtMode mode = serviceJwtService == null
                ? PipelineServiceJwtMode.HMAC_ONLY
                : serviceJwtService.mode();
        boolean useHmac = !mode.jwtEnabled() || mode.hmacRequired()
                || (secret != null && !secret.isBlank());
        if (mode == PipelineServiceJwtMode.HMAC_ONLY) {
            if (endpoint == null || endpoint.isBlank() || secret == null || secret.isBlank()) {
                if (!required) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "API-key lifecycle sync is not configured; key {0} may remain active in the pipeline.",
                            key.getId());
                    return;
                }
                throw synchronizationUnavailable(
                        "API-key lifecycle synchronization is not configured; no state change was applied.");
            }
        } else if (endpoint == null || endpoint.isBlank()) {
            if (!required) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "API-key lifecycle sync is not configured; key {0} may remain active in the pipeline.",
                        key.getId());
                return;
            }
            throw synchronizationUnavailable(
                    "API-key lifecycle synchronization is not configured; no state change was applied.");
        }
        if ((useHmac && (secret == null || secret.isBlank()))
                || (secret != null && !secret.isBlank()
                        && secret.getBytes(StandardCharsets.UTF_8).length < 32)) {
            throw synchronizationUnavailable(
                    "API-key lifecycle synchronization is misconfigured; no state change was applied.");
        }

        URI syncUri;
        try {
            syncUri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw synchronizationUnavailable(
                    "API-key lifecycle synchronization is misconfigured; no state change was applied.");
        }
        if (!isSecureOrDevelopmentBridge(syncUri)) {
            throw synchronizationUnavailable(
                    "API-key lifecycle synchronization must use HTTPS outside the local development bridge.");
        }
        if (!SYNC_PATH.equals(syncUri.getRawPath())
                || syncUri.getQuery() != null
                || syncUri.getFragment() != null) {
            throw synchronizationUnavailable(
                    "API-key lifecycle synchronization is misconfigured; no state change was applied.");
        }

        String path = LIFECYCLE_PATH + key.getId() + "/" + operation;
        URI uri = syncUri.resolve(path);
        if (key.getVersion() < 1 || key.getVersion() > Integer.MAX_VALUE) {
            throw synchronizationUnavailable(
                    "API-key lifecycle source version is outside the supported range; no state change was applied.");
        }
        LifecyclePayload payload = new LifecyclePayload(
                key.getOrganizationId().toString(),
                key.getProjectId().toString(),
                key.getEnvironmentId().toString(),
                scopedTenantId(key),
                key.getVersion());
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw synchronizationUnavailable(
                    "API-key lifecycle synchronization failed; no state change was applied.", exception);
        }
        String idempotencyKey = lifecycleIdempotencyKey(key, operation);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        addRequestContextHeaders(requestBuilder);
        if (mode.jwtEnabled()) {
            try {
                requestBuilder.header(
                        "Authorization",
                        "Bearer " + serviceJwtService.issueKeyLifecycleToken(operation));
            } catch (RuntimeException exception) {
                throw synchronizationUnavailable(
                        "API-key lifecycle service authentication is unavailable; no state change was applied.");
            }
        }
        if (useHmac) {
            String canonical = timestamp + "\nPOST\n" + path + "\n" + body;
            String signature = sign(secret, canonical);
            requestBuilder.header("X-PesaGuard-Timestamp", timestamp)
                    .header("X-PesaGuard-Signature", signature);
        }
        try {
            PipelineResponse response = sendWithRetries(requestBuilder.build());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String details = pipelineErrorDetails(response.body());
                throw synchronizationUnavailable(
                        "The API-key pipeline rejected lifecycle synchronization (HTTP "
                                + response.statusCode()
                                + (details == null ? "" : ", " + details)
                                + "); no state change was applied.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw synchronizationUnavailable(
                    "API-key lifecycle synchronization was interrupted; no state change was applied.", exception);
        } catch (java.io.IOException exception) {
            throw synchronizationUnavailable(
                    "The API-key pipeline is unavailable; no state change was applied. Please retry shortly.",
                    exception);
        }
    }

    private boolean isSecureOrDevelopmentBridge(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        if (productionProfileActive || !"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        return "localhost".equalsIgnoreCase(uri.getHost())
                || "127.0.0.1".equals(uri.getHost())
                || ("dashboard_api".equalsIgnoreCase(uri.getHost()) && uri.getPort() == 5001)
                || ("host.docker.internal".equalsIgnoreCase(uri.getHost()) && uri.getPort() == 5001);
    }

    private static void addRequestContextHeaders(HttpRequest.Builder requestBuilder) {
        var requestId = RequestContext.currentRequestIdIfPresent();
        if (requestId != null) {
            requestBuilder.header(RequestContext.REQUEST_ID_HEADER, requestId.toString());
        }
        String correlationId = RequestContext.currentCorrelationId();
        if (correlationId != null && !correlationId.isBlank()) {
            requestBuilder.header(RequestContext.CORRELATION_ID_HEADER, correlationId);
        }
        String traceparent = RequestContext.currentTraceparent();
        if (traceparent != null && !traceparent.isBlank()) {
            requestBuilder.header(RequestContext.TRACEPARENT_HEADER, traceparent);
        }
    }

    private PipelineResponse sendWithRetries(HttpRequest request)
            throws java.io.IOException, InterruptedException {
        if (!bulkhead.tryAcquire()) {
            throw new BulkheadRejectedException();
        }
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                HttpResponse<InputStream> response;
                try {
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                } catch (java.io.IOException exception) {
                    if (!isRetryableTransportFailure(exception) || attempt == MAX_ATTEMPTS) {
                        throw exception;
                    }
                    retrySleeper.sleep(retryDelay(attempt));
                    continue;
                }
                boolean retryableStatus = isRetryableStatus(response.statusCode());
                if (retryableStatus && attempt < MAX_ATTEMPTS) {
                    discardResponseBody(response.body());
                    retrySleeper.sleep(retryDelay(attempt));
                    continue;
                }
                try (InputStream responseBody = response.body()) {
                    String body = new String(
                            responseBody.readNBytes(MAX_RESPONSE_BODY_BYTES), StandardCharsets.UTF_8);
                    return new PipelineResponse(response.statusCode(), body);
                } catch (java.io.IOException exception) {
                    if (response.statusCode() >= 200 && response.statusCode() < 300
                            && attempt < MAX_ATTEMPTS && isRetryableTransportFailure(exception)) {
                        retrySleeper.sleep(retryDelay(attempt));
                        continue;
                    }
                    if (!retryableStatus) {
                        return new PipelineResponse(response.statusCode(), "");
                    }
                    throw exception;
                }
            }
            throw new java.io.IOException("API-key sync attempts exhausted.");
        } finally {
            bulkhead.release();
        }
    }

    private static void discardResponseBody(InputStream responseBody) {
        try (InputStream body = responseBody) {
            body.readNBytes(MAX_RESPONSE_BODY_BYTES);
        } catch (java.io.IOException ignored) {
            // The status already establishes a retryable failure; discard its bounded body.
        }
    }

    static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429
                || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    static boolean isRetryableTransportFailure(java.io.IOException exception) {
        return !(exception instanceof javax.net.ssl.SSLException)
                && !(exception instanceof BulkheadRejectedException);
    }

    private Duration retryDelay(int retryNumber) {
        long exponentialNanos = RETRY_BASE_DELAY.toNanos() * (1L << (retryNumber - 1));
        long cappedNanos = Math.min(exponentialNanos, RETRY_MAX_DELAY.toNanos());
        double sample = Math.max(0.0, Math.min(1.0, jitter.getAsDouble()));
        return Duration.ofNanos((long) (cappedNanos * sample));
    }

    private static void sleep(Duration duration) throws InterruptedException {
        long millis = duration.toMillis();
        int nanos = (int) (duration.minusMillis(millis).toNanos());
        Thread.sleep(millis, nanos);
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(Duration delay) throws InterruptedException;
    }

    private static final class BulkheadRejectedException extends java.io.IOException {
        private static final long serialVersionUID = 1L;
    }

    private record PipelineResponse(int statusCode, String body) {
    }

    private static String lifecycleIdempotencyKey(ApiKey key, String operation) {
        String source = String.join(
                "\n",
                "developer-platform-key-lifecycle-v1",
                key.getId().toString(),
                operation,
                Long.toString(key.getVersion()));
        try {
            return "pgl_" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String syncIdempotencyKey(ApiKey key) {
        String source = String.join(
                "\n",
                "developer-platform-key-sync-v1",
                key.getId().toString(),
                Long.toString(key.getVersion()));
        try {
            return "pgs_" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String pipelineErrorDetails(String responseBody) {
        try {
            var error = objectMapper.readTree(responseBody);
            String code = safeErrorCode(error.path("error").asString(null));
            String reason = safeErrorCode(error.path("reason").asString(null));
            if (code == null) {
                return null;
            }
            return reason == null ? code : code + ":" + reason;
        } catch (JacksonException exception) {
            return null;
        }
    }

    private static String safeErrorCode(String value) {
        return value != null && value.matches("[a-z0-9_]{1,64}") ? value : null;
    }

    private static BusinessException synchronizationUnavailable(String message) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "API_KEY_PIPELINE_UNAVAILABLE", message);
    }

    private static BusinessException synchronizationUnavailable(String message, Throwable cause) {
        BusinessException exception = synchronizationUnavailable(message);
        exception.initCause(cause);
        return exception;
    }

    private static String sign(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", exception);
        }
    }

    private static String scopedTenantId(ApiKey key) {
        return DeveloperTenantId.derive(
                key.getOrganizationId().toString(),
                key.getProjectId().toString(),
                key.getEnvironmentId().toString());
    }

    private record SyncPayload(
            String key_id,
            String tenant_id,
            String organization_id,
            String project_id,
            String environment_id,
            long source_version,
            String key_hash,
            String key_prefix,
            java.util.List<String> scopes,
            java.util.List<String> ip_allowlist,
            String status,
            String expires_at) {
    }

    private record LifecyclePayload(
            String organization_id,
            String project_id,
            String environment_id,
            String tenant_id,
            long source_version) {
    }
}
