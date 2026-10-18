package com.pesaguard.backend.tenancy;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class TenantEventPublisher {

    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public TenantEventPublisher(ApplicationEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public void publish(
            UUID organizationId,
            UUID actorId,
            String eventType,
            UUID resourceId,
            UUID requestId,
            Map<String, ?> metadata) {
        publisher.publishEvent(new TenantEvent(
                organizationId,
                organizationId,
                actorId,
                eventType,
                resourceId,
                requestId,
                clock.instant(),
                metadata));
    }
}