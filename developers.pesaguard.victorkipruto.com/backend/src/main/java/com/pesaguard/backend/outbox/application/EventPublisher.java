package com.pesaguard.backend.outbox.application;

import com.pesaguard.backend.outbox.domain.OutboxEvent;

/**
 * Destination for events leaving the platform.
 *
 * <p>An interface rather than a Kafka call so the relay can be exercised against
 * an in-memory double, and so the transactional guarantee -- which lives in the
 * outbox table -- can be proven without standing up a broker. The atomicity that
 * matters does not depend on the transport; it depends on the row being written
 * in the same transaction as the domain change.
 *
 * <p>Implementations must be idempotent from the caller's perspective, or at
 * least tolerate the same {@code eventId} arriving twice: publication is
 * at-least-once by design.
 */
public interface EventPublisher {

    /**
     * Publishes one event.
     *
     * @throws EventPublishException if the broker rejected or was unreachable.
     * The caller records the failure and retries; it must not mark the event
     * published.
     */
    void publish(OutboxEvent event);

    /** Runtime failure from the broker. Never carries credentials or payloads. */
    class EventPublishException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public EventPublishException(String message, Throwable cause) {
            super(message, cause);
        }

        public EventPublishException(String message) {
            super(message);
        }
    }
}