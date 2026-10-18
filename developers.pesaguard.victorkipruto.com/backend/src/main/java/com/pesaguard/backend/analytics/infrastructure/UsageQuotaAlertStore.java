package com.pesaguard.backend.analytics.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Idempotent persistence for per-environment, per-hour usage alerts. */
@Repository
public class UsageQuotaAlertStore {

    private final JdbcTemplate jdbcTemplate;

    public UsageQuotaAlertStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean claim(UUID environmentId, UUID organizationId, String alertType, Instant periodStart) {
        return jdbcTemplate.update("""
                insert into usage_quota_alerts(environment_id, organization_id, alert_type, period_start)
                values (?, ?, ?, ?)
                on conflict (environment_id, alert_type, period_start) do nothing
                """, environmentId, organizationId, alertType, periodStart) == 1;
    }

    public int deleteBefore(Instant cutoff) {
        return jdbcTemplate.update("delete from usage_quota_alerts where created_at < ?", cutoff);
    }
}
