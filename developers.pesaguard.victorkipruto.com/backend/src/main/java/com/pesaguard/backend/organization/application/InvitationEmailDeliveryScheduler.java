package com.pesaguard.backend.organization.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.organization.infrastructure.InvitationEmailDeliveryRepository;

@Component
public class InvitationEmailDeliveryScheduler {

    private static final int BATCH_SIZE = 20;

    private final InvitationEmailDeliveryRepository deliveryRepository;
    private final InvitationEmailDeliveryProcessor processor;
    private final java.time.Clock clock;

    public InvitationEmailDeliveryScheduler(InvitationEmailDeliveryRepository deliveryRepository,
            InvitationEmailDeliveryProcessor processor, java.time.Clock clock) {
        this.deliveryRepository = deliveryRepository;
        this.processor = processor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pesaguard.organization.invitation-email-dispatch-ms:5000}")
    public void deliverDue() {
        for (var deliveryId : deliveryRepository.findDueIds(clock.instant(), PageRequest.of(0, BATCH_SIZE))) {
            processor.process(deliveryId);
        }
    }
}
