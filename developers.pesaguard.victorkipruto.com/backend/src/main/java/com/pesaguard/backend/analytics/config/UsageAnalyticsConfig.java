package com.pesaguard.backend.analytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.pesaguard.backend.analytics.application.UsageEventPersistenceService;
import com.pesaguard.backend.analytics.application.UsageEventRecorder;

/**
 * Wires the usage recorder's background writer to the database.
 *
 * <p>Declared as a {@code @Bean} rather than component-scanned so the buffer can
 * be constructed with a real, transactional sink. The buffer is a
 * {@link UsageEventRecorder} and the sink is a
 * {@link UsageEventPersistenceService}, which only exists once Spring's
 * transaction and repository infrastructure is available.
 */
@Configuration
@EnableScheduling
public class UsageAnalyticsConfig {

    /**
     * The single shared buffer. Its background thread drains into the database.
     *
     * <p>Capacity is bounded deliberately. See {@link UsageEventRecorder} for why
     * an unbounded queue is unsafe here.
     */
    @Bean(destroyMethod = "destroy")
    public UsageEventRecorder usageEventRecorder(UsageEventPersistenceService persistence,
            org.springframework.core.env.Environment environment) {
        int capacity = capacity(environment);
        return new UsageEventRecorder(capacity, persistence::persist);
    }

    private int capacity(org.springframework.core.env.Environment environment) {
        String configured = environment.getProperty("pesaguard.usage.buffer-capacity");
        if (configured == null || configured.isBlank()) {
            return 10_000;
        }
        try {
            return Integer.parseInt(configured.trim());
        } catch (NumberFormatException malformed) {
            // Fall back rather than fail startup: losing a little buffering
            // capacity is better than refusing to boot over a config typo.
            return 10_000;
        }
    }
}