package com.pesaguard.backend.billing.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "billing_webhook_events")
@IdClass(BillingWebhookEventId.class)
public class BillingWebhookEvent {

    @Id
    @Column(nullable = false, length = 24)
    private String provider;

    @Id
    @Column(name = "provider_event_id", nullable = false, length = 255)
    private String providerEventId;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected BillingWebhookEvent() {
    }

    public void markProcessed(Instant now) {
        if (processedAt == null) processedAt = now;
    }

    public String getProvider() { return provider; }
    public String getProviderEventId() { return providerEventId; }
    public Instant getProcessedAt() { return processedAt; }
}
