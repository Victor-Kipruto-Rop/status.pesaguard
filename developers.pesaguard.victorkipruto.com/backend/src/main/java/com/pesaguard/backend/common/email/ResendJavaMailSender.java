package com.pesaguard.backend.common.email;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sends Spring text and HTML emails through the Resend HTTP API.
 */
public final class ResendJavaMailSender extends JavaMailSenderImpl {

    private static final URI RESEND_EMAILS_ENDPOINT = URI.create("https://api.resend.com/emails");

    private final String apiKey;
    private final String fromAddress;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final HttpClient httpClient;

    public ResendJavaMailSender(String apiKey, String fromAddress, ObjectMapper objectMapper) {
        this(apiKey, fromAddress, objectMapper, RESEND_EMAILS_ENDPOINT);
    }

    ResendJavaMailSender(String apiKey, String fromAddress, ObjectMapper objectMapper, URI endpoint) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("RESEND_API_KEY is required when Resend email delivery is enabled");
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalStateException("PESAGUARD_MAIL_FROM is required when Resend email delivery is enabled");
        }
        this.apiKey = apiKey.trim();
        this.fromAddress = fromAddress.trim();
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void send(SimpleMailMessage message) {
        send(new SimpleMailMessage[] { message });
    }

    @Override
    public void send(SimpleMailMessage... messages) {
        if (messages == null) {
            throw new MailSendException("Email message batch is required");
        }
        for (SimpleMailMessage message : messages) {
            sendOne(message);
        }
    }

    private void sendOne(SimpleMailMessage message) {
        if (message == null) {
            throw new MailSendException("Email message is required");
        }
        String[] recipients = message.getTo();
        if (recipients == null || recipients.length == 0) {
            throw new MailSendException("At least one email recipient is required");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", message.getFrom() == null ? fromAddress : message.getFrom());
        payload.put("to", List.of(recipients));
        addAddresses(payload, "cc", message.getCc());
        addAddresses(payload, "bcc", message.getBcc());
        if (message.getReplyTo() != null && !message.getReplyTo().isBlank()) {
            payload.put("reply_to", message.getReplyTo());
        }
        payload.put("subject", message.getSubject() == null ? "" : message.getSubject());
        payload.put("text", message.getText() == null ? "" : message.getText());
        payload.put("html", EmailBranding.brandedHtml(message.getSubject(), message.getText()));
        sendPayload(payload, null);
    }

    @Override
    public void send(MimeMessage message) {
        send(new MimeMessage[] { message });
    }

    @Override
    public void send(MimeMessage... messages) {
        if (messages == null) {
            throw new MailSendException("Email message batch is required");
        }
        for (MimeMessage message : messages) {
            sendOne(message);
        }
    }

    private void sendOne(MimeMessage message) {
        if (message == null) {
            throw new MailSendException("Email message is required");
        }
        try {
            message.saveChanges();
            Address[] recipients = message.getRecipients(Message.RecipientType.TO);
            if (recipients == null || recipients.length == 0) {
                throw new MailSendException("At least one email recipient is required");
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("from", firstAddress(message.getFrom(), fromAddress));
            payload.put("to", addressList(recipients));
            addAddresses(payload, "cc", addressList(message.getRecipients(Message.RecipientType.CC)));
            addAddresses(payload, "bcc", addressList(message.getRecipients(Message.RecipientType.BCC)));
            Address[] replyTo = message.getReplyTo();
            if (replyTo != null && replyTo.length > 0) {
                payload.put("reply_to", addressList(replyTo).get(0));
            }
            payload.put("subject", message.getSubject() == null ? "" : message.getSubject());

            StringBuilder text = new StringBuilder();
            StringBuilder html = new StringBuilder();
            collectBody(message.getContentType(), message.getContent(), text, html);
            payload.put("text", text.toString());
            if (!html.isEmpty()) {
                payload.put("html", html.toString());
            }
            sendPayload(payload, idempotencyKey(message));
        } catch (MessagingException | IOException exception) {
            throw new MailSendException("Unable to read email message for Resend", exception);
        }
    }

    private static void collectBody(
            String contentType, Object content, StringBuilder text, StringBuilder html)
            throws MessagingException, IOException {
        if (content instanceof Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                BodyPart part = multipart.getBodyPart(index);
                collectBody(part.getContentType(), part.getContent(), text, html);
            }
        } else if (content instanceof String value) {
            if (contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/html")) {
                html.append(value);
            } else if (contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/plain")) {
                text.append(value);
            }
        }
    }

    private static String firstAddress(Address[] addresses, String fallback) {
        return addresses == null || addresses.length == 0 ? fallback : addresses[0].toString();
    }

    private static List<String> addressList(Address[] addresses) {
        return addresses == null ? List.of() : Arrays.stream(addresses).map(Address::toString).toList();
    }

    private static String idempotencyKey(MimeMessage message) throws MessagingException {
        String[] values = message.getHeader("X-Resend-Idempotency-Key");
        if (values == null || values.length == 0 || values[0].isBlank()) {
            return null;
        }
        String key = values[0].trim();
        if (key.length() > 256 || key.chars().anyMatch(Character::isISOControl)) {
            throw new MailSendException("Email idempotency key is invalid");
        }
        return key;
    }

    private void sendPayload(Map<String, Object> payload, String idempotencyKey) {

        final String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException exception) {
            throw new MailSendException("Unable to encode email request for Resend", exception);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json");
        if (idempotencyKey != null) {
            requestBuilder.header("Idempotency-Key", idempotencyKey);
        }
        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        final HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MailSendException("Resend email request was interrupted", exception);
        } catch (IOException exception) {
            throw new MailSendException("Unable to reach the Resend email API", exception);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MailSendException("Resend email API returned HTTP " + response.statusCode());
        }
        try {
            JsonNode result = objectMapper.readTree(response.body());
            if (result == null || !result.path("id").isTextual() || result.path("id").asText().isBlank()) {
                throw new MailSendException("Resend email API returned no message id");
            }
        } catch (IOException exception) {
            throw new MailSendException("Resend email API returned an invalid response", exception);
        }
    }

    private static void addAddresses(Map<String, Object> payload, String key, String[] addresses) {
        if (addresses != null && addresses.length > 0) {
            List<String> values = new ArrayList<>(addresses.length);
            for (String address : addresses) {
                values.add(address);
            }
            payload.put(key, values);
        }
    }

    private static void addAddresses(Map<String, Object> payload, String key, List<String> addresses) {
        if (!addresses.isEmpty()) {
            payload.put(key, addresses);
        }
    }
}
