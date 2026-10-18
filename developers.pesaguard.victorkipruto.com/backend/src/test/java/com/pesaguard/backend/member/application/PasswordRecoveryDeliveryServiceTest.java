package com.pesaguard.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

class PasswordRecoveryDeliveryServiceTest {

    @Test
    void sendsBrandedHtmlAndPlainTextWithSingleUseResetLink() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        PasswordRecoveryDeliveryService service = new PasswordRecoveryDeliveryService(
                mailSender, "no-reply@pesaguard.co.ke", "https://developers.example.test/");

        service.send("developer@example.com", "single-use-token");

        verify(mailSender).send(message);
        assertThat(message.getFrom()[0].toString()).isEqualTo("no-reply@pesaguard.co.ke");
        assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("developer@example.com");
        assertThat(message.getReplyTo()[0].toString()).isEqualTo("support@pesaguard.co.ke");
        assertThat(message.getSubject()).isEqualTo("Reset your PesaGuard password");

        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        message.saveChanges();
        collectContent(message.getContentType(), message.getContent(), text, html);
        assertThat(text.toString())
                .contains("https://developers.example.test/reset-password#reset-password=single-use-token")
                .contains("one hour");
        assertThat(html.toString())
                .contains("PesaGuard")
                .contains("Reset Password")
                .contains("https://developers.example.test/reset-password#reset-password=single-use-token")
                .contains("support@pesaguard.co.ke");
    }

    private static void collectContent(
            String contentType, Object content, StringBuilder text, StringBuilder html) throws Exception {
        if (content instanceof Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                BodyPart part = multipart.getBodyPart(index);
                collectContent(part.getContentType(), part.getContent(), text, html);
            }
        } else if (content instanceof String value) {
            if (contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/html")) {
                html.append(value);
            } else if (contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain")) {
                text.append(value);
            }
        }
    }
}
