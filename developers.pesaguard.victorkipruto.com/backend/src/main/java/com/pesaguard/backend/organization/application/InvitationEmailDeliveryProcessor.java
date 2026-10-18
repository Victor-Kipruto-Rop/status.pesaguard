package com.pesaguard.backend.organization.application;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.organization.domain.InvitationEmailDelivery;
import com.pesaguard.backend.organization.domain.InvitationStatus;
import com.pesaguard.backend.organization.infrastructure.InvitationEmailDeliveryRepository;
import com.pesaguard.backend.organization.infrastructure.OrganizationInvitationRepository;
import com.pesaguard.backend.security.credentials.SecretEncryptionService;

@Service
public class InvitationEmailDeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(InvitationEmailDeliveryProcessor.class);

    private final InvitationEmailDeliveryRepository deliveryRepository;
    private final OrganizationInvitationRepository invitationRepository;
    private final InvitationDeliveryService deliveryService;
    private final SecretEncryptionService encryption;
    private final Clock clock;

    public InvitationEmailDeliveryProcessor(InvitationEmailDeliveryRepository deliveryRepository,
            OrganizationInvitationRepository invitationRepository,
            InvitationDeliveryService deliveryService, SecretEncryptionService encryption, Clock clock) {
        this.deliveryRepository = deliveryRepository;
        this.invitationRepository = invitationRepository;
        this.deliveryService = deliveryService;
        this.encryption = encryption;
        this.clock = clock;
    }

    @Transactional
    public void process(UUID deliveryId) {
        InvitationEmailDelivery candidate = deliveryRepository.findById(deliveryId).orElse(null);
        if (candidate == null) return;
        var invitation = invitationRepository.findByIdForUpdate(candidate.getInvitationId()).orElse(null);
        InvitationEmailDelivery delivery = deliveryRepository
                .findDueByIdForUpdate(deliveryId, clock.instant()).orElse(null);
        if (delivery == null) return;
        if (invitation == null
                || invitation.getStatus() != InvitationStatus.PENDING
                || !invitation.getExpiresAt().isAfter(clock.instant())
                || !invitation.getTokenHash().equals(delivery.getTokenHash())) {
            delivery.cancel();
            return;
        }
        try {
            deliveryService.send(delivery.getRecipientEmail(), encryption.decrypt(delivery.getTokenCiphertext()),
                    delivery.getOrganizationName(), delivery.getInviterName(), delivery.getRole(),
                    delivery.getExpiresAt());
            delivery.markSent(clock.instant());
        } catch (MailException failure) {
            delivery.markFailed(failure.getClass().getSimpleName(), clock.instant());
            log.error("Invitation email delivery attempt failed deliveryId={} attempt={} errorType={}",
                    delivery.getId(), delivery.getAttemptCount(), failure.getClass().getSimpleName());
        }
    }
}
