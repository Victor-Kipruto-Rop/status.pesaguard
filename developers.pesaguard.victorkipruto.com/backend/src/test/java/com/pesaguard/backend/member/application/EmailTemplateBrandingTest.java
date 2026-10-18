package com.pesaguard.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pesaguard.backend.common.email.EmailBranding;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

@ExtendWith(MockitoExtension.class)
class EmailTemplateBrandingTest {

    @Mock
    private org.springframework.mail.javamail.JavaMailSender mailSender;

    @BeforeEach
    void prepareMailSender() throws Exception {
        when(mailSender.createMimeMessage())
                .thenAnswer(ignored -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void accountSecurityEmailUsesTheSharedLogoAndGreenTheme() throws Exception {
        AccountEmailDeliveryService service = accountEmailService();

        service.sendEmailVerificationCode("developer@example.com", "123456");

        String html = sentHtml();
        assertThat(html).contains(EmailBranding.logoMarkup().trim())
                .contains("#2fa943")
                .contains("123456");
    }

    @Test
    void invitationsUseTheSharedLogoAndEscapeOrganizationContent() throws Exception {
        AccountEmailDeliveryService service = accountEmailService();

        service.sendInvitation("developer@example.com", "invite-token", "<Studio>",
                "Victor", "DEVELOPER", java.time.Instant.parse("2026-04-01T00:00:00Z"));

        String html = sentHtml();
        assertThat(html).contains(EmailBranding.logoMarkup().trim())
                .contains("&lt;Studio&gt;")
                .doesNotContain("<strong><Studio></strong>");
    }

    @Test
    void passwordResetUsesTheSharedLogo() throws Exception {
        PasswordRecoveryDeliveryService service = new PasswordRecoveryDeliveryService(
                mailSender, "no-reply@pesaguard.co.ke", "https://developers.example.com");

        service.send("developer@example.com", "reset-token");

        assertThat(sentHtml()).contains(EmailBranding.logoMarkup().trim())
                .contains("#2fa943");
    }

    private AccountEmailDeliveryService accountEmailService() {
        return new AccountEmailDeliveryService(mailSender, "no-reply@pesaguard.co.ke",
                "https://developers.example.com");
    }

    private String sentHtml() throws Exception {
        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(message.capture());
        message.getValue().saveChanges();
        return findHtml(message.getValue().getContent());
    }

    private static String findHtml(Object content) throws Exception {
        if (!(content instanceof Multipart multipart)) {
            return "";
        }
        for (int index = 0; index < multipart.getCount(); index++) {
            BodyPart part = multipart.getBodyPart(index);
            if (part.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("text/html")) {
                return String.valueOf(part.getContent());
            }
            String nestedHtml = findHtml(part.getContent());
            if (!nestedHtml.isEmpty()) {
                return nestedHtml;
            }
        }
        return "";
    }
}
