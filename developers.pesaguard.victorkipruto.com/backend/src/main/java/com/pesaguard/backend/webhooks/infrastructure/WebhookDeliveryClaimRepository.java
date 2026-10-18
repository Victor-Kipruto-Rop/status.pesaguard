package com.pesaguard.backend.webhooks.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WebhookDeliveryClaimRepository {

    private final JdbcTemplate jdbcTemplate;

    public WebhookDeliveryClaimRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Atomically leases one event/subscription pair across all application nodes.
     * The separate lease table preserves the append-only delivery-attempt history.
     */
    @Transactional
    public boolean tryClaim(UUID eventId, UUID subscriptionId, Instant now, Instant claimedUntil) {
        int rows = jdbcTemplate.update("""
                insert into webhook_delivery_claims (event_id, subscription_id, claimed_until)
                values (?, ?, ?)
                on conflict (event_id, subscription_id) do update
                    set claimed_until = excluded.claimed_until
                    where webhook_delivery_claims.claimed_until <= ?
                """,
                eventId, subscriptionId, Timestamp.from(claimedUntil), Timestamp.from(now));
        return rows == 1;
    }

    @Transactional
    public void release(UUID eventId, UUID subscriptionId) {
        jdbcTemplate.update("""
                delete from webhook_delivery_claims
                where event_id = ? and subscription_id = ?
                """, eventId, subscriptionId);
    }
}
