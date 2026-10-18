package com.pesaguard.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pesaguard.billing")
public record BillingProperties(
        Stripe stripe,
        PayHero payHero,
        Daraja daraja,
        AirtelMoney airtelMoney,
        String manualInstructions) {

    public BillingProperties {
        stripe = stripe == null ? new Stripe(null, null, null, null, null) : stripe;
        payHero = payHero == null ? new PayHero(null, null, null, null) : payHero;
        daraja = daraja == null ? new Daraja(null, null, null, null, null, null) : daraja;
        airtelMoney = airtelMoney == null ? new AirtelMoney(null, null, null, null, null, null) : airtelMoney;
        manualInstructions = clean(manualInstructions);
    }

    public record Stripe(String baseUrl, String secretKey, String webhookSecret,
            String successUrl, String cancelUrl) {
        public String normalizedBaseUrl() {
            return valueOr(baseUrl, "https://api.stripe.com");
        }

        public boolean isConfigured() {
            return has(secretKey) && has(webhookSecret) && has(successUrl) && has(cancelUrl);
        }
    }

    public record PayHero(String baseUrl, String apiToken, String channelId, String callbackUrl) {
        public String normalizedBaseUrl() {
            return valueOr(baseUrl, "https://backend.payhero.co.ke");
        }

        public boolean isConfigured() {
            return has(apiToken) && has(channelId) && has(callbackUrl);
        }
    }

    public record Daraja(String baseUrl, String consumerKey, String consumerSecret,
            String businessShortCode, String passkey, String callbackUrl) {
        public String normalizedBaseUrl() {
            return valueOr(baseUrl, "https://sandbox.safaricom.co.ke");
        }

        public boolean isConfigured() {
            return has(consumerKey) && has(consumerSecret) && has(businessShortCode)
                    && has(passkey) && has(callbackUrl);
        }
    }

    public record AirtelMoney(String baseUrl, String clientId, String clientSecret,
            String country, String currency, String callbackUrl) {
        public String normalizedBaseUrl() {
            return valueOr(baseUrl, "https://openapiuat.airtel.africa");
        }

        public boolean isConfigured() {
            return has(clientId) && has(clientSecret) && has(country) && has(currency) && has(callbackUrl);
        }
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static String valueOr(String value, String fallback) {
        return has(value) ? value.trim().replaceAll("/+$", "") : fallback;
    }

    private static String clean(String value) {
        return has(value) ? value.trim() : null;
    }
}
