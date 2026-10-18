package com.pesaguard.backend.security.authentication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import com.pesaguard.backend.common.exception.BusinessException;

@Component
public class BreachedPasswordChecker implements PasswordBreachChecker {

    private static final String HIBP_RANGE_URL = "https://api.pwnedpasswords.com";
    private static final String USER_AGENT = "PesaGuard-Developer-Platform";
    private final RestClient client;

    public BreachedPasswordChecker() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder()
                .baseUrl(HIBP_RANGE_URL)
                .requestFactory(requestFactory)
                .defaultHeader("Add-Padding", "true")
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }

    @Override
    public boolean isCompromised(String password) {
        String sha1 = sha1(password);
        String response;
        try {
            response = client.get()
                    .uri("/range/{prefix}", sha1.substring(0, 5))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException exception) {
            throw unavailable();
        }
        if (response == null) {
            throw unavailable();
        }
        String suffix = sha1.substring(5);
        for (String line : response.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] entry = line.trim().split(":", -1);
            if (entry.length != 2 || !entry[0].matches("[A-F0-9]{35}")
                    || !entry[1].matches("[0-9]+")) {
                throw unavailable();
            }
            if (entry[0].toUpperCase(Locale.ROOT).equals(suffix)) {
                try {
                    return Long.parseLong(entry[1]) > 0;
                } catch (NumberFormatException exception) {
                    throw unavailable();
                }
            }
        }
        return false;
    }

    private static String sha1(String password) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable for breach range checking.", exception);
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PASSWORD_BREACH_CHECK_UNAVAILABLE",
                "We could not check this password against known breach data. Please try again shortly.");
    }
}
