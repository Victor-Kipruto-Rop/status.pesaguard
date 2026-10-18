package com.pesaguard.backend.billing.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.billing.domain.BillingWebhookEvent;
import com.pesaguard.backend.billing.domain.BillingWebhookEventId;

public interface BillingWebhookEventRepository extends JpaRepository<BillingWebhookEvent, BillingWebhookEventId> {

    @Modifying
    @Query(value = """
            insert into billing_webhook_events(provider, provider_event_id, payload_hash)
            values (:provider, :eventId, :payloadHash)
            on conflict (provider, provider_event_id) do nothing
            """, nativeQuery = true)
    int registerOnce(@Param("provider") String provider, @Param("eventId") String eventId,
            @Param("payloadHash") String payloadHash);
}
