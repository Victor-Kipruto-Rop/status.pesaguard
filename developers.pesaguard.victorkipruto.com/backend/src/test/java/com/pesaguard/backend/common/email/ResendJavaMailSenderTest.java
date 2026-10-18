package com.pesaguard.backend.common.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.MimeMessageHelper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.internet.MimeMessage;

class ResendJavaMailSenderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    private HttpServer server;
    private int responseStatus;
    private String responseBody;

    @BeforeEach
    void startServer() throws IOException {
        responseStatus = 200;
        responseBody = "{\"id\":\"email-test-id\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsSimpleMailMessageToResendAndUsesVerifiedResponseId() throws Exception {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo("developer@example.com");
        message.setCc("team@example.com");
        message.setReplyTo("support@pesaguard.co.ke");
        message.setSubject("Verify your account");
        message.setText("Your verification code is 123456.");

        sender("re_test_secret").send(message);

        assertThat(authorization.get()).isEqualTo("Bearer re_test_secret");
        JsonNode payload = objectMapper.readTree(requestBody.get());
        assertThat(payload.path("from").asText()).isEqualTo("no-reply@pesaguard.co.ke");
        assertThat(payload.path("to").get(0).asText()).isEqualTo("developer@example.com");
        assertThat(payload.path("cc").get(0).asText()).isEqualTo("team@example.com");
        assertThat(payload.path("reply_to").asText()).isEqualTo("support@pesaguard.co.ke");
        assertThat(payload.path("subject").asText()).isEqualTo("Verify your account");
        assertThat(payload.path("text").asText()).contains("123456");
        assertThat(payload.path("html").asText())
                .contains("https://pesaguard.co.ke/logo/logo.svg")
                .contains("#2fa943")
                .contains("123456");
    }

    @Test
    void escapesSimpleMessageContentInTheBrandedHtml() throws Exception {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo("developer@example.com");
        message.setSubject("<script>unsafe</script>");
        message.setText("<img src=x>\nAlert details");

        sender("re_test_secret").send(message);

        JsonNode payload = objectMapper.readTree(requestBody.get());
        assertThat(payload.path("html").asText())
                .contains("&lt;script&gt;unsafe&lt;/script&gt;")
                .contains("&lt;img src=x&gt;<br>Alert details")
                .doesNotContain("<script>")
                .doesNotContain("<img src=x>");
    }

    @Test
    void sendsHtmlAndPlainTextMimeMessageToResend() throws Exception {
        ResendJavaMailSender sender = sender("re_test_secret");
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(
                message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
        helper.setFrom("no-reply@pesaguard.co.ke");
        helper.setTo("developer@example.com");
        helper.setSubject("Reset your password");
        helper.setText("Plain reset link: https://example.test/reset", "<a href=\"https://example.test/reset\">Reset Password</a>");

        sender.send(message);

        JsonNode payload = objectMapper.readTree(requestBody.get());
        assertThat(payload.path("to").get(0).asText()).isEqualTo("developer@example.com");
        assertThat(payload.path("subject").asText()).isEqualTo("Reset your password");
        assertThat(payload.path("text").asText()).contains("Plain reset link");
        assertThat(payload.path("html").asText())
                .contains("<a href=\"https://example.test/reset\">Reset Password</a>");
    }

    @Test
    void forwardsTheNotificationIdempotencyKeyToResend() throws Exception {
        ResendJavaMailSender sender = sender("re_test_secret");
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom("no-reply@pesaguard.co.ke");
        helper.setTo("developer@example.com");
        helper.setSubject("Key revoked");
        helper.setText("A key was revoked.", "<p>A key was revoked.</p>");
        message.setHeader("X-Resend-Idempotency-Key", "notification-event-123");

        sender.send(message);

        assertThat(idempotencyKey.get()).isEqualTo("notification-event-123");
    }

    @Test
    void rejectsAnInvalidIdempotencyKey() throws Exception {
        ResendJavaMailSender sender = sender("re_test_secret");
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom("no-reply@pesaguard.co.ke");
        helper.setTo("developer@example.com");
        helper.setSubject("Key revoked");
        helper.setText("A key was revoked.", "<p>A key was revoked.</p>");
        message.setHeader("X-Resend-Idempotency-Key", "x".repeat(257));

        assertThatThrownBy(() -> sender.send(message))
                .isInstanceOf(MailSendException.class)
                .hasMessageContaining("idempotency key is invalid");
    }

    @Test
    void rejectsNonSuccessProviderResponseWithoutExposingResponseBody() {
        responseStatus = 401;
        responseBody = "{\"message\":\"secret provider details\"}";
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo("developer@example.com");
        message.setSubject("Test");
        message.setText("Test");

        assertThatThrownBy(() -> sender("re_test_secret").send(message))
                .isInstanceOf(MailSendException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("secret provider details")
                .hasMessageNotContaining("re_test_secret");
    }

    @Test
    void requiresAnApiKeyWhenResendIsSelected() {
        assertThatThrownBy(() -> new ResendJavaMailSender("", "no-reply@pesaguard.co.ke",
                objectMapper, endpoint()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
    }

    private ResendJavaMailSender sender(String apiKey) {
        return new ResendJavaMailSender(apiKey, "no-reply@pesaguard.co.ke", objectMapper, endpoint());
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/emails");
    }
}
