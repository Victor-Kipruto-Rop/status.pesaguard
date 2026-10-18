package com.pesaguard.backend.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import com.pesaguard.backend.config.BillingProperties;

class BillingGatewayTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private static final String WEBHOOK_SECRET = "whsec_test_secret";
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void acceptsOnlyFreshStripeSignaturesOverTheExactRawBody() throws Exception {
        byte[] body = "{\"id\":\"evt_123\"}".getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(NOW.getEpochSecond());
        String validHeader = signature(body, timestamp);
        BillingGateway gateway = new BillingGateway(configuredProperties(), null, clock);

        assertThat(gateway.verifyStripeSignature(body, validHeader)).isTrue();
        assertThat(gateway.verifyStripeSignature(body, "t=" + timestamp + ",v1=invalid," + validHeader.substring(
                validHeader.indexOf(",") + 1))).isTrue();
        assertThat(gateway.verifyStripeSignature(
                "{\"id\":\"evt_124\"}".getBytes(StandardCharsets.UTF_8), validHeader)).isFalse();
        assertThat(gateway.verifyStripeSignature(body,
                signature(body, Long.toString(NOW.minusSeconds(301).getEpochSecond())))).isFalse();
        assertThat(gateway.verifyStripeSignature(body, "t=not-a-timestamp,v1=invalid")).isFalse();
    }

    @Test
    void reportsPaymentProvidersUnavailableUntilAllRequiredConfigurationExists() {
        BillingGateway gateway = new BillingGateway(
                new BillingProperties(null, null, null, null, null), null, clock);

        assertThat(gateway.available("STRIPE")).isFalse();
        assertThat(gateway.available("PAYHERO")).isFalse();
        assertThat(gateway.available("DARAJA")).isFalse();
        assertThat(gateway.available("AIRTEL_MONEY")).isFalse();
        assertThat(gateway.available("MANUAL")).isFalse();
    }

    private static BillingProperties configuredProperties() {
        return new BillingProperties(
                new BillingProperties.Stripe("https://api.stripe.com", "sk_test", WEBHOOK_SECRET,
                        "https://example.test/success", "https://example.test/cancel"),
                null, null, null, null);
    }

    private static String signature(byte[] body, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
