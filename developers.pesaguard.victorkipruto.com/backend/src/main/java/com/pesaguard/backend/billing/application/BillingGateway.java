package com.pesaguard.backend.billing.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.pesaguard.backend.billing.domain.BillingInvoice;
import com.pesaguard.backend.billing.domain.BillingPayment;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.config.BillingProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class BillingGateway {

    private static final DateTimeFormatter DARAJA_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Africa/Nairobi"));

    private final BillingProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BillingGateway(BillingProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public boolean available(String provider) {
        return switch (provider) {
            case "STRIPE" -> properties.stripe().isConfigured();
            case "PAYHERO" -> properties.payHero().isConfigured();
            case "DARAJA" -> properties.daraja().isConfigured();
            case "AIRTEL_MONEY" -> properties.airtelMoney().isConfigured();
            case "MANUAL" -> properties.manualInstructions() != null;
            default -> false;
        };
    }

    public String manualInstructions() {
        return properties.manualInstructions();
    }

    public GatewayResult start(String provider, BillingInvoice invoice, BillingPayment payment,
            String phoneNumber) {
        return switch (provider) {
            case "STRIPE" -> startStripe(invoice, payment);
            case "PAYHERO" -> startPayHero(invoice, payment, phoneNumber);
            case "DARAJA" -> startDaraja(invoice, payment, phoneNumber);
            case "AIRTEL_MONEY" -> startAirtel(invoice, payment, phoneNumber);
            case "MANUAL" -> new GatewayResult(null, null);
            default -> throw unavailable(provider);
        };
    }

    public PaymentState refresh(String provider, BillingPayment payment, BillingInvoice invoice) {
        return switch (provider) {
            case "STRIPE" -> refreshStripe(payment);
            case "PAYHERO" -> refreshPayHero(payment);
            case "DARAJA" -> refreshDaraja(payment);
            case "AIRTEL_MONEY" -> refreshAirtel(payment);
            case "MANUAL" -> PaymentState.PENDING;
            default -> throw unavailable(provider);
        };
    }

    public boolean verifyStripeSignature(byte[] rawBody, String signatureHeader) {
        String secret = properties.stripe().webhookSecret();
        if (secret == null || secret.isBlank() || signatureHeader == null || rawBody == null) return false;
        String timestamp = null;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("t")) timestamp = pair[1];
            if (pair.length == 2 && pair[0].equals("v1")) signatures.add(pair[1]);
        }
        if (timestamp == null || signatures.isEmpty()) return false;
        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException invalid) {
            return false;
        }
        long now = clock.instant().getEpochSecond();
        if (timestampSeconds < now - 300 || timestampSeconds > now + 300) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(rawBody)).getBytes(StandardCharsets.US_ASCII);
            return signatures.stream().anyMatch(signature -> MessageDigest.isEqual(
                    expected, signature.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("Unable to verify the Stripe webhook signature.", unavailable);
        }
    }

    public JsonNode readJson(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException malformed) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_PROVIDER_EVENT", "The payment provider event body is invalid.");
        }
    }

    private GatewayResult startStripe(BillingInvoice invoice, BillingPayment payment) {
        requireConfigured("STRIPE");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("line_items[0][price_data][currency]", invoice.getCurrency().toLowerCase(Locale.ROOT));
        form.add("line_items[0][price_data][unit_amount]", Long.toString(invoice.getAmountMinor()));
        form.add("line_items[0][price_data][product_data][name]", invoice.getInvoiceNumber());
        form.add("line_items[0][quantity]", "1");
        form.add("client_reference_id", payment.getId().toString());
        form.add("metadata[invoice_id]", invoice.getId().toString());
        form.add("metadata[payment_id]", payment.getId().toString());
        form.add("success_url", properties.stripe().successUrl());
        form.add("cancel_url", properties.stripe().cancelUrl());

        JsonNode response = client(properties.stripe().normalizedBaseUrl()).post().uri("/v1/checkout/sessions")
                .headers(headers -> {
                    headers.setBasicAuth(properties.stripe().secretKey(), "");
                    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                    headers.set("Idempotency-Key", payment.getIdempotencyKey());
                }).body(form).retrieve().body(JsonNode.class);
        String id = requiredText(response, "id");
        String url = requiredText(response, "url");
        requireHttps(url);
        return new GatewayResult(id, url);
    }

    private GatewayResult startPayHero(BillingInvoice invoice, BillingPayment payment, String phone) {
        requireCurrency(invoice, "KES", "PAYHERO");
        requireWholeMajorAmount(invoice, "PAYHERO");
        JsonNode response = client(properties.payHero().normalizedBaseUrl()).post().uri("/api/v2/payments")
                .headers(headers -> {
                    headers.set(HttpHeaders.AUTHORIZATION, "Basic " + properties.payHero().apiToken());
                    headers.setContentType(MediaType.APPLICATION_JSON);
                }).body(Map.of(
                        "amount", BigDecimal.valueOf(invoice.getAmountMinor(), 2),
                        "phone_number", normalizeKenyanPhone(phone),
                        "channel_id", properties.payHero().channelId(),
                        "provider", "m-pesa",
                        "external_reference", payment.getId().toString(),
                        "callback_url", properties.payHero().callbackUrl()))
                .retrieve().body(JsonNode.class);
        return new GatewayResult(requiredFirstText(response,
                "CheckoutRequestID", "checkout_request_id", "reference"), null);
    }

    private GatewayResult startDaraja(BillingInvoice invoice, BillingPayment payment, String phone) {
        requireCurrency(invoice, "KES", "DARAJA");
        requireWholeMajorAmount(invoice, "DARAJA");
        BillingProperties.Daraja config = properties.daraja();
        requireConfigured("DARAJA");
        JsonNode access = client(config.normalizedBaseUrl()).get()
                .uri("/oauth/v1/generate?grant_type=client_credentials")
                .headers(headers -> headers.setBasicAuth(config.consumerKey(), config.consumerSecret()))
                .retrieve().body(JsonNode.class);
        String accessToken = requiredText(access, "access_token");
        String timestamp = DARAJA_TIMESTAMP.format(clock.instant());
        String password = Base64.getEncoder().encodeToString(
                (config.businessShortCode() + config.passkey() + timestamp).getBytes(StandardCharsets.UTF_8));
        String normalizedPhone = normalizeKenyanPhone(phone);
        JsonNode response = client(config.normalizedBaseUrl()).post().uri("/mpesa/stkpush/v1/processrequest")
                .headers(headers -> {
                    headers.setBearerAuth(accessToken);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                }).body(Map.ofEntries(
                        Map.entry("BusinessShortCode", config.businessShortCode()),
                        Map.entry("Password", password),
                        Map.entry("Timestamp", timestamp),
                        Map.entry("TransactionType", "CustomerPayBillOnline"),
                        Map.entry("Amount", invoice.getAmountMinor() / 100),
                        Map.entry("PartyA", normalizedPhone),
                        Map.entry("PartyB", config.businessShortCode()),
                        Map.entry("PhoneNumber", normalizedPhone),
                        Map.entry("CallBackURL", config.callbackUrl()),
                        Map.entry("AccountReference", invoice.getInvoiceNumber()),
                        Map.entry("TransactionDesc", invoice.getDescription())))
                .retrieve().body(JsonNode.class);
        return new GatewayResult(requiredText(response, "CheckoutRequestID"), null);
    }

    private GatewayResult startAirtel(BillingInvoice invoice, BillingPayment payment, String phone) {
        BillingProperties.AirtelMoney config = properties.airtelMoney();
        requireConfigured("AIRTEL_MONEY");
        if (!config.currency().equalsIgnoreCase(invoice.getCurrency())) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "UNSUPPORTED_INVOICE_CURRENCY", "Airtel Money is configured for a different invoice currency.");
        }
        JsonNode tokenResponse = client(config.normalizedBaseUrl()).post().uri("/auth/oauth2/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("client_id", config.clientId(), "client_secret", config.clientSecret(),
                        "grant_type", "client_credentials"))
                .retrieve().body(JsonNode.class);
        String accessToken = requiredText(tokenResponse, "access_token");
        String transactionId = payment.getId().toString();
        JsonNode response = client(config.normalizedBaseUrl()).post().uri("/merchant/v1/payments/")
                .headers(headers -> {
                    headers.setBearerAuth(accessToken);
                    headers.set("X-Country", config.country());
                    headers.set("X-Currency", config.currency());
                    headers.setContentType(MediaType.APPLICATION_JSON);
                }).body(Map.of(
                        "reference", invoice.getInvoiceNumber(),
                        "subscriber", Map.of("country", config.country(), "currency", config.currency(),
                                "msisdn", digitsOnly(phone)),
                        "transaction", Map.of("amount", BigDecimal.valueOf(invoice.getAmountMinor(), 2), "id", transactionId)))
                .retrieve().body(JsonNode.class);
        if (response == null || response.isMissingNode()) {
            throw providerFailure("AIRTEL_MONEY");
        }
        return new GatewayResult(transactionId, null);
    }

    private PaymentState refreshStripe(BillingPayment payment) {
        if (payment.getProviderReference() == null) return PaymentState.PENDING;
        JsonNode response = client(properties.stripe().normalizedBaseUrl())
                .get().uri("/v1/checkout/sessions/" + payment.getProviderReference())
                .headers(headers -> headers.setBasicAuth(properties.stripe().secretKey(), ""))
                .retrieve().body(JsonNode.class);
        String paid = text(response, "payment_status");
        String sessionStatus = text(response, "status");
        if ("paid".equals(paid)) return PaymentState.PAID;
        if ("expired".equals(sessionStatus)) return PaymentState.FAILED;
        return PaymentState.PENDING;
    }

    private PaymentState refreshPayHero(BillingPayment payment) {
        if (payment.getProviderReference() == null) return PaymentState.PENDING;
        JsonNode response = client(properties.payHero().normalizedBaseUrl()).get()
                .uri(UriComponentsBuilder.fromPath("/api/v2/transaction-status")
                        .queryParam("reference", payment.getProviderReference()).build().toUri())
                .headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, "Basic " + properties.payHero().apiToken()))
                .retrieve().body(JsonNode.class);
        return parseStatus(firstText(response, "Status", "status"));
    }

    private PaymentState refreshDaraja(BillingPayment payment) {
        if (payment.getProviderReference() == null) return PaymentState.PENDING;
        BillingProperties.Daraja config = properties.daraja();
        JsonNode access = client(config.normalizedBaseUrl()).get()
                .uri("/oauth/v1/generate?grant_type=client_credentials")
                .headers(headers -> headers.setBasicAuth(config.consumerKey(), config.consumerSecret()))
                .retrieve().body(JsonNode.class);
        String accessToken = requiredText(access, "access_token");
        String timestamp = DARAJA_TIMESTAMP.format(clock.instant());
        String password = Base64.getEncoder().encodeToString(
                (config.businessShortCode() + config.passkey() + timestamp).getBytes(StandardCharsets.UTF_8));
        JsonNode response = client(config.normalizedBaseUrl()).post().uri("/mpesa/stkpushquery/v1/query")
                .headers(headers -> {
                    headers.setBearerAuth(accessToken);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                }).body(Map.of("BusinessShortCode", config.businessShortCode(), "Password", password,
                        "Timestamp", timestamp, "CheckoutRequestID", payment.getProviderReference()))
                .retrieve().body(JsonNode.class);
        String resultCode = text(response, "ResultCode");
        if ("0".equals(resultCode)) return PaymentState.PAID;
        if (resultCode != null && !"".equals(resultCode)) return PaymentState.FAILED;
        return PaymentState.PENDING;
    }

    private PaymentState refreshAirtel(BillingPayment payment) {
        if (payment.getProviderReference() == null) return PaymentState.PENDING;
        BillingProperties.AirtelMoney config = properties.airtelMoney();
        JsonNode tokenResponse = client(config.normalizedBaseUrl()).post().uri("/auth/oauth2/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("client_id", config.clientId(), "client_secret", config.clientSecret(),
                        "grant_type", "client_credentials"))
                .retrieve().body(JsonNode.class);
        String accessToken = requiredText(tokenResponse, "access_token");
        JsonNode response = client(config.normalizedBaseUrl()).get()
                .uri("/standard/v1/payments/" + payment.getProviderReference())
                .headers(headers -> {
                    headers.setBearerAuth(accessToken);
                    headers.set("X-Country", config.country());
                    headers.set("X-Currency", config.currency());
                }).retrieve().body(JsonNode.class);
        String status = firstText(response, "status", "data.transaction.status");
        return parseStatus(status);
    }

    private RestClient client(String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    private void requireConfigured(String provider) {
        if (!available(provider)) throw unavailable(provider);
    }

    private static void requireCurrency(BillingInvoice invoice, String expected, String provider) {
        if (!expected.equals(invoice.getCurrency())) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "UNSUPPORTED_INVOICE_CURRENCY", provider + " is only enabled for " + expected + " invoices.");
        }
    }

    private static void requireWholeMajorAmount(BillingInvoice invoice, String provider) {
        if (invoice.getAmountMinor() % 100 != 0) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "UNSUPPORTED_INVOICE_AMOUNT",
                    provider + " requires whole-unit KES amounts. Ask the operator to issue a corrected invoice.");
        }
    }

    private static String normalizeKenyanPhone(String phone) {
        String digits = digitsOnly(phone);
        if (digits.startsWith("0") && digits.length() == 10) digits = "254" + digits.substring(1);
        if (digits.startsWith("7") && digits.length() == 9) digits = "254" + digits;
        if (digits.startsWith("1") && digits.length() == 9) digits = "254" + digits;
        if (!digits.matches("254[17][0-9]{8}")) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_PHONE_NUMBER", "Enter a Kenyan mobile number in 07..., 01..., or +254... format.");
        }
        return digits;
    }

    private static String digitsOnly(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D", "");
    }

    private static PaymentState parseStatus(String value) {
        if (value == null) return PaymentState.PENDING;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESS", "SUCCESSFUL", "COMPLETED", "PAID", "TS" -> PaymentState.PAID;
            case "FAILED", "FAILURE", "CANCELLED", "CANCELED", "TF" -> PaymentState.FAILED;
            default -> PaymentState.PENDING;
        };
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) throw providerFailure("PAYMENT_PROVIDER");
        return value;
    }

    private static String requiredFirstText(JsonNode node, String... fields) {
        String value = firstText(node, fields);
        if (value == null) throw providerFailure("PAYMENT_PROVIDER");
        return value;
    }

    private static String text(JsonNode node, String path) {
        if (node == null || path == null) return null;
        JsonNode value = node;
        for (String segment : path.split("\\.")) {
            value = value.path(segment);
        }
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private static void requireHttps(String url) {
        if (url == null || !url.startsWith("https://")) {
            throw providerFailure("STRIPE");
        }
    }

    private static BusinessException unavailable(String provider) {
        return new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "PAYMENT_PROVIDER_UNAVAILABLE", provider + " is not configured for this deployment.");
    }

    private static BusinessException providerFailure(String provider) {
        return new BusinessException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "PAYMENT_PROVIDER_ERROR", provider + " did not return a valid payment response.");
    }

    public record GatewayResult(String reference, String checkoutUrl) {
    }

    public enum PaymentState {
        PENDING,
        PAID,
        FAILED
    }
}
