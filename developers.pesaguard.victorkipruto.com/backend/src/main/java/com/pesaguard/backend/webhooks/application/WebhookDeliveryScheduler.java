package com.pesaguard.backend.webhooks.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.pesaguard.backend.events.domain.EventDelivery;
import com.pesaguard.backend.events.domain.EventSubscription;
import com.pesaguard.backend.events.infrastructure.EventDeliveryRepository;
import com.pesaguard.backend.events.infrastructure.EventSubscriptionRepository;
import com.pesaguard.backend.explorer.security.OutboundTargetGuard;
import com.pesaguard.backend.explorer.security.PinnedTargetDnsResolver;
import com.pesaguard.backend.explorer.security.UnsafeTargetException;
import com.pesaguard.backend.security.credentials.SecretEncryptionService;
import com.pesaguard.backend.webhooks.delivery.RetryPolicy;
import com.pesaguard.backend.webhooks.domain.WebhookEndpoint;
import com.pesaguard.backend.webhooks.infrastructure.WebhookDeliveryClaimRepository;
import com.pesaguard.backend.webhooks.infrastructure.WebhookEndpointRepository;
import com.pesaguard.backend.webhooks.security.WebhookSignature;
import com.pesaguard.backend.notifications.application.WebhookDeliveryNotificationEmitter;

/**
 * Delivers real published outbox events. Each result is stored as an immutable
 * attempt row; retries create a new row rather than rewriting delivery history.
 */
@Component
public class WebhookDeliveryScheduler {

    private static final int BATCH_SIZE = 50;
    private static final Duration CLAIM_TTL = Duration.ofMinutes(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final EventSubscriptionRepository subscriptionRepository;
    private final EventDeliveryRepository deliveryRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryClaimRepository claimRepository;
    private final SecretEncryptionService encryption;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final WebhookDeliveryNotificationEmitter notifications;
    private final RetryPolicy retryPolicy = RetryPolicy.defaults();
    private final OutboundTargetGuard targetGuard = new OutboundTargetGuard(java.util.Set.of(), false);

    public WebhookDeliveryScheduler(EventSubscriptionRepository subscriptionRepository,
            EventDeliveryRepository deliveryRepository, WebhookEndpointRepository endpointRepository,
            WebhookDeliveryClaimRepository claimRepository, SecretEncryptionService encryption,
            ObjectMapper objectMapper, Clock clock,
            WebhookDeliveryNotificationEmitter notifications) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.claimRepository = claimRepository;
        this.encryption = encryption;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${pesaguard.webhooks.dispatch-interval-ms:5000}")
    public void dispatchDue() {
        List<Object[]> due = subscriptionRepository.findDueDeliveries(
                clock.instant(), PageRequest.of(0, BATCH_SIZE));
        for (Object[] pair : due) {
            if (pair.length != 2 || !(pair[0] instanceof com.pesaguard.backend.outbox.domain.OutboxEvent event)
                    || !(pair[1] instanceof EventSubscription subscription)) {
                throw new IllegalStateException("Webhook delivery query returned an invalid result.");
            }
            dispatchNow(event, subscription);
        }
    }

    public boolean dispatchNow(com.pesaguard.backend.outbox.domain.OutboxEvent event,
            EventSubscription subscription) {
        Instant now = clock.instant();
        if (!claimRepository.tryClaim(event.getEventId(), subscription.getId(),
                now, now.plus(CLAIM_TTL))) {
            return false;
        }
        try {
            return dispatchClaimed(event, subscription);
        } finally {
            claimRepository.release(event.getEventId(), subscription.getId());
        }
    }

    private boolean dispatchClaimed(com.pesaguard.backend.outbox.domain.OutboxEvent event,
            EventSubscription subscription) {
        if (!subscription.delivers()
                || !subscription.matchesEnvironment(event.getEnvironmentId())) {
            return false;
        }
        Optional<WebhookEndpoint> endpoint = findEndpoint(event, subscription);
        if (endpoint.isEmpty()) {
            recordPermanentFailure(event, subscription, "ENDPOINT_NOT_FOUND", 0, 0);
            return true;
        }
        if (!"ACTIVE".equals(endpoint.get().getStatus())) return false;
        JsonNode payload;
        try {
            payload = objectMapper.readTree(event.getPayload());
        } catch (JacksonException exception) {
            recordPermanentFailure(event, subscription, "INVALID_EVENT_PAYLOAD", 0, 0);
            return true;
        }
        if (!subscription.matchesEnvironment(uuid(payload.get("environmentId")))
                || !subscription.matches(attributes(payload))) {
            return false;
        }
        final String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "id", event.getEventId().toString(),
                    "type", event.getEventType(),
                    "version", event.getEventVersion(),
                    "createdAt", event.getCreatedAt().toString(),
                    "data", payload));
        } catch (JacksonException exception) {
            recordPermanentFailure(event, subscription, "EVENT_ENVELOPE_INVALID", 0, 0);
            return true;
        }

        final OutboundTargetGuard.ValidatedTarget validatedTarget;
        try {
            validatedTarget = targetGuard.validate(endpoint.get().getUrl());
        } catch (UnsafeTargetException exception) {
            recordPermanentFailure(event, subscription, "UNSAFE_TARGET", 0, 0);
            return true;
        }
        int attemptNumber = deliveryRepository
                .findTopByEventIdAndSubscriptionIdOrderByAttemptDesc(
                        event.getEventId(), subscription.getId())
                .map(previous -> previous.getAttempt() + 1)
                .orElse(1);
        EventDelivery attempt = attemptNumber == 1
                ? EventDelivery.firstAttempt(event.getEventId(), event.getEventType(), subscription)
                : deliveryRepository.findTopByEventIdAndSubscriptionIdOrderByAttemptDesc(
                        event.getEventId(), subscription.getId())
                        .orElseThrow()
                        .nextAttempt();
        Instant started = clock.instant();
        String secret = encryption.decrypt(endpoint.get().getSigningSecretCiphertext());
        String signature = WebhookSignature.sign(secret, body, started);

        try {
            int statusCode = postToPinnedTarget(validatedTarget, body, event, attempt, signature);
            int latency = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0, Duration.between(started, clock.instant()).toMillis()));
            if (statusCode >= 200 && statusCode < 300) {
                attempt.markDelivered(statusCode, latency);
                deliveryRepository.save(attempt);
            } else {
                recordFailure(event, subscription, "HTTP_" + statusCode,
                        statusCode, latency, attempt);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordFailure(event, subscription, "DELIVERY_INTERRUPTED", 0, null, attempt);
        } catch (IOException exception) {
            recordFailure(event, subscription, "NETWORK_ERROR", 0, null, attempt);
        }
        return true;
    }

    private int postToPinnedTarget(OutboundTargetGuard.ValidatedTarget target,
            String body, com.pesaguard.backend.outbox.domain.OutboxEvent event,
            EventDelivery attempt, String signature) throws IOException, InterruptedException {
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.of(REQUEST_TIMEOUT))
                .build();
        try (HttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PinnedTargetDnsResolver(target))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .build())
                .build();
                CloseableHttpClient client = HttpClients.custom()
                        .setConnectionManager(connectionManager)
                        .setDefaultRequestConfig(requestConfig)
                        .disableRedirectHandling()
                        .build()) {
            org.apache.hc.client5.http.classic.methods.HttpPost request =
                    new org.apache.hc.client5.http.classic.methods.HttpPost(target.uri());
            request.setHeader("PesaGuard-Event", event.getEventType());
            request.setHeader("PesaGuard-Event-ID", event.getEventId().toString());
            request.setHeader("PesaGuard-Delivery-ID", attempt.getId().toString());
            request.setHeader("PesaGuard-Signature", signature);
            request.setHeader("Idempotency-Key", event.getEventId().toString());
            request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
            return client.execute(request, response -> {
                EntityUtils.consume(response.getEntity());
                return response.getCode();
            });
        }
    }

    private Optional<WebhookEndpoint> findEndpoint(
            com.pesaguard.backend.outbox.domain.OutboxEvent event,
            EventSubscription subscription) {
        try {
            return endpointRepository.findByIdAndOrganizationIdAndProjectIdAndEnvironmentId(
                            java.util.UUID.fromString(subscription.getEndpointId()),
                            event.getOrganizationId(), event.getProjectId(),
                            subscription.getEnvironmentId());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private void recordPermanentFailure(
            com.pesaguard.backend.outbox.domain.OutboxEvent event,
            EventSubscription subscription, String errorCode, int responseCode, int latency) {
        EventDelivery attempt = nextAttempt(event, subscription);
        attempt.markPermanentlyFailed(responseCode, errorCode, latency);
        deliveryRepository.save(attempt);
    }

    private void recordFailure(com.pesaguard.backend.outbox.domain.OutboxEvent event,
            EventSubscription subscription, String errorCode, int responseCode, Integer latency) {
        recordFailure(event, subscription, errorCode, responseCode, latency,
                nextAttempt(event, subscription));
    }

    private EventDelivery nextAttempt(com.pesaguard.backend.outbox.domain.OutboxEvent event,
            EventSubscription subscription) {
        int attemptNumber = deliveryRepository
                .findTopByEventIdAndSubscriptionIdOrderByAttemptDesc(
                        event.getEventId(), subscription.getId())
                .map(previous -> previous.getAttempt() + 1)
                .orElse(1);
        return attemptNumber == 1
                ? EventDelivery.firstAttempt(event.getEventId(), event.getEventType(), subscription)
                : deliveryRepository.findTopByEventIdAndSubscriptionIdOrderByAttemptDesc(
                        event.getEventId(), subscription.getId())
                        .orElseThrow()
                        .nextAttempt();
    }

    private void recordFailure(com.pesaguard.backend.outbox.domain.OutboxEvent event,
            EventSubscription subscription, String errorCode, int responseCode,
            Integer latency, EventDelivery attempt) {
        int effectiveLatency = latency == null ? 0 : latency;
        if (responseCode > 0 && !retryPolicy.isRetryableStatus(responseCode)) {
            attempt.markPermanentlyFailed(responseCode, errorCode, effectiveLatency);
        } else if (retryPolicy.shouldRetry(attempt.getAttempt())) {
            Instant nextAttempt = clock.instant().plus(
                    retryPolicy.delayFor(attempt.getAttempt()));
            attempt.markFailed(responseCode, errorCode, null, effectiveLatency, nextAttempt, false);
        } else {
            attempt.markFailed(responseCode, errorCode, null, effectiveLatency, null, true);
        }
        deliveryRepository.save(attempt);
        if (attempt.getStatus() == com.pesaguard.backend.events.domain.DeliveryStatus.DEAD_LETTERED) {
            notifications.deadLettered(attempt.getOrganizationId(), attempt.getEventType(),
                    attempt.getEndpointId(), attempt.getAttempt());
        }
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        try {
            return java.util.UUID.fromString(node.asText());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Map<String, String> attributes(JsonNode payload) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        for (String key : List.of("projectId", "environmentId", "resourceType", "resourceId", "status")) {
            JsonNode value = payload.get(key);
            if (value != null && value.isValueNode()) result.put(key, value.asText());
        }
        return result;
    }

}
