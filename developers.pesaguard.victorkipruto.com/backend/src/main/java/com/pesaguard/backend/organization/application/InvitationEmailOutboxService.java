package com.pesaguard.backend.organization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.organization.domain.InvitationEmailDelivery;
import com.pesaguard.backend.organization.domain.InvitationEmailDeliveryStatus;
import com.pesaguard.backend.organization.infrastructure.InvitationEmailDeliveryRepository;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;
import com.pesaguard.backend.security.credentials.SecretEncryptionService;

@Service
public class InvitationEmailOutboxService {

    private final InvitationEmailDeliveryRepository repository;
    private final SecretEncryptionService encryption;
    private final Clock clock;

    public InvitationEmailOutboxService(InvitationEmailDeliveryRepository repository,
            SecretEncryptionService encryption, Clock clock) {
        this.repository = repository;
        this.encryption = encryption;
        this.clock = clock;
    }

    @Transactional
    public void enqueue(UUID invitationId, String email, String token, String tokenHash,
            String organizationName, String inviterName, String role, Instant expiresAt) {
        Instant now = clock.instant();
        repository.save(InvitationEmailDelivery.queue(
                invitationId, email, organizationName, inviterName, role,
                tokenHash, encryption.encrypt(token), expiresAt, now));
    }

    @Transactional(readOnly = true)
    public String deliveryStatus(UUID invitationId) {
        InvitationEmailDeliveryStatus status = repository
                .findFirstByInvitationIdOrderByCreatedAtDesc(invitationId)
                .map(InvitationEmailDelivery::getStatus)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation email delivery"));
        return status == InvitationEmailDeliveryStatus.PENDING ? "QUEUED" : status.name();
    }
}
