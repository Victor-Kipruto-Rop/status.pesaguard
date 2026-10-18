package com.pesaguard.backend.billing.domain;

import java.io.Serializable;
import java.util.Objects;

public class BillingWebhookEventId implements Serializable {

    private String provider;
    private String providerEventId;

    public BillingWebhookEventId() {
    }

    public BillingWebhookEventId(String provider, String providerEventId) {
        this.provider = provider;
        this.providerEventId = providerEventId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BillingWebhookEventId that)) return false;
        return Objects.equals(provider, that.provider) && Objects.equals(providerEventId, that.providerEventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, providerEventId);
    }
}
