package com.pesaguard.backend.outbox.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.outbox.application.OutboxRelay;

/**
 * Drives the outbox relay on a fixed interval.
 *
 * <p>Every instance runs this. That is intentional rather than a bug: two relays
 * may claim the same row, but publishing twice is the tolerated failure in an
 * at-least-once design, whereas a leader-elected single relay adds a failover
 * problem (a paused or partitioned leader stops all event publication) to avoid a
 * duplicate that consumers must already tolerate. Correctness comes from the
 * immutable {@code event_id}, not from mutual exclusion between relays.
 *
 * <p>The drain is bounded per pass, so a large backlog is worked through over
 * several intervals instead of one unbounded loop that would hold database
 * connections and starve request handling.
 */
@Component
@ConditionalOnProperty(name = "pesaguard.messaging.outbox.relay-enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${pesaguard.messaging.outbox.drain-interval-ms:5000}")
    public void drain() {
        try {
            int published = relay.drain();
            if (published > 0) {
                log.info("Outbox relay published {} event(s)", published);
            }
        } catch (RuntimeException failure) {
            // Swallowed deliberately. An exception escaping a @Scheduled method
            // is logged by Spring and the task continues, but logging here too
            // keeps the failure attributable to the relay rather than to the
            // scheduler, and avoids rethrowing on every tick.
            log.error("Outbox relay drain failed type={}",
                    failure.getClass().getSimpleName());
        }
    }
}