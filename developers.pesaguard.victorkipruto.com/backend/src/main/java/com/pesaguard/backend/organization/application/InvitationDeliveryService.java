package com.pesaguard.backend.organization.application;

import org.springframework.stereotype.Service;

import java.time.Instant;

import com.pesaguard.backend.member.application.AccountEmailDeliveryService;

/** Sends invitation links without logging the bearer token. */
@Service
public class InvitationDeliveryService {

    private final AccountEmailDeliveryService emailDelivery;

    public InvitationDeliveryService(AccountEmailDeliveryService emailDelivery) {
        this.emailDelivery = emailDelivery;
    }

    public void send(String email, String token, String organizationName,
            String inviterName, String role, Instant expiresAt) {
        emailDelivery.sendInvitation(email, token, organizationName, inviterName, role, expiresAt);
    }
}
