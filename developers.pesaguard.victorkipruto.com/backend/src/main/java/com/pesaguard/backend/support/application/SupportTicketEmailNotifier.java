package com.pesaguard.backend.support.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.support.domain.SupportTicket;

@Component
public class SupportTicketEmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketEmailNotifier.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String supportAddress;

    public SupportTicketEmailNotifier(JavaMailSender mailSender,
            @Value("${pesaguard.identity.from-email}") String fromAddress,
            @Value("${pesaguard.support.notification-email:}") String supportAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.supportAddress = supportAddress;
    }

    public void requireConfigured() {
        if (supportAddress == null || supportAddress.isBlank()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SUPPORT_EMAIL_NOT_CONFIGURED",
                    "Support ticket email notifications are not configured. Contact the platform administrator.");
        }
    }

    public void notifyNewTicket(SupportTicket ticket) {
        requireConfigured();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(supportAddress.trim());
        message.setSubject("New PesaGuard support ticket " + ticket.getPublicId());
        message.setText("""
                A developer submitted a support request.

                Ticket: %s
                Organization: %s
                Requester: %s
                Category: %s
                Priority: %s
                Subject: %s

                %s
                """.formatted(ticket.getPublicId(), ticket.getOrganizationId(),
                ticket.getContactEmail(), ticket.getCategory(), ticket.getPriority(),
                ticket.getSubject(), ticket.getDescription()));
        try {
            mailSender.send(message);
        } catch (MailException failure) {
            log.error("support ticket email delivery failed ticketId={} cause={}",
                    ticket.getPublicId(), failure.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SUPPORT_EMAIL_DELIVERY_FAILED",
                    "The support notification could not be sent. Please retry shortly.");
        }
    }
}
