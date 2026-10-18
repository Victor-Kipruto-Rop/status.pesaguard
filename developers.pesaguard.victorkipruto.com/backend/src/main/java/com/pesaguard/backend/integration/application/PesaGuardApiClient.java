package com.pesaguard.backend.integration.application;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.pesaguard.backend.environment.domain.EnvironmentType;

@Component
public class PesaGuardApiClient {

    private static final String CONTEXT_PATH = "/api/v1/key-data/auth/context";
    private static final int MAX_CONTEXT_BYTES = 32 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper objectMapper;

    public PesaGuardApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TestResponse test(String baseUrl, String apiKey, EnvironmentType expectedEnvironment, UUID requestId)
            throws IOException, InterruptedException {
        URI endpoint = URI.create(baseUrl + CONTEXT_PATH);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("X-Request-ID", requestId.toString())
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response = HTTP.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (var stream = response.body()) {
            body = stream.readNBytes(MAX_CONTEXT_BYTES + 1);
        }
        if (body.length > MAX_CONTEXT_BYTES) {
            return TestResponse.failed("INVALID_RESPONSE", "The API returned an invalid connection response.");
        }
        if (response.statusCode() == 401) {
            return TestResponse.failed("INVALID_CREDENTIALS", "The configured API credential was rejected.");
        }
        if (response.statusCode() == 403) {
            return TestResponse.failed("CONTEXT_ACCESS_DENIED",
                    "The API key could not read its authentication context. Confirm api:read and the key's access restrictions.");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return TestResponse.failed("PROVIDER_ERROR", "The PesaGuard API could not verify this connection.");
        }

        JsonNode context;
        try {
            context = objectMapper.readTree(body);
        } catch (Exception exception) {
            return TestResponse.failed("INVALID_RESPONSE", "The API returned an invalid connection response.");
        }
        String actualEnvironment = readEnvironment(context);
        if (actualEnvironment == null) {
            return TestResponse.failed("CONTEXT_UNVERIFIED",
                    "The API did not provide environment context, so the connection could not be verified.");
        }
        if (!matches(expectedEnvironment, actualEnvironment)) {
            return TestResponse.failed("ENVIRONMENT_MISMATCH",
                    "The API credential is connected to a different environment.");
        }
        return TestResponse.successful();
    }

    private static String readEnvironment(JsonNode root) {
        for (JsonNode node : new JsonNode[] { root, root.path("data"), root.path("context") }) {
            for (String field : new String[] {
                    "environmentType", "environment_type", "environmentTier", "environment_tier" }) {
                JsonNode value = node.path(field);
                if (value.isTextual()) return value.asText();
            }
            JsonNode environment = node.path("environment");
            if (environment.isTextual()) return environment.asText();
            for (String field : new String[] { "type", "tier", "name" }) {
                JsonNode value = environment.path(field);
                if (value.isTextual()) return value.asText();
            }
        }
        return null;
    }

    private static boolean matches(EnvironmentType expected, String actual) {
        String value = actual.trim().toUpperCase(java.util.Locale.ROOT);
        return expected.name().equals(value);
    }

    public record TestResponse(boolean success, String failureCategory, String message) {
        static TestResponse successful() {
            return new TestResponse(true, null, "The PesaGuard API connection was verified.");
        }

        static TestResponse failed(String category, String message) {
            return new TestResponse(false, category, message);
        }
    }
}
