package com.pesaguard.backend.loadtest.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import com.pesaguard.backend.loadtest.domain.LoadTestConfiguration;
import com.pesaguard.backend.loadtest.domain.LoadTestThreshold;

@Component
public class K6ScriptGenerator {
    private final ObjectMapper objectMapper;

    public K6ScriptGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String generate(LoadTestConfiguration test, String baseUrl, String safeRunId) {
        return generate(test, baseUrl, safeRunId, test.getTargetVus());
    }

    public String generate(LoadTestConfiguration test, String baseUrl, String safeRunId, int allocatedVus) {
        String url = literal(baseUrl + test.getEndpointPath());
        String method = literal(test.getHttpMethod());
        String body = test.getRequestBody() == null ? "null" : literal(test.getRequestBody());
        String headers = literal(test.getHeaders());
        String stages = literal(test.getStages().stream()
                .map(stage -> Map.of("duration", stage.durationSeconds() + "s",
                        "target", Math.min(allocatedVus,
                                (int) Math.ceil(stage.targetVus() * (allocatedVus / (double) test.getTargetVus())))))
                .toList());
        String thresholds = literal(k6Thresholds(test.getThresholds()));
        String keyHeader = test.getApiKeyId() == null ? ""
                : "  headers.Authorization = `Bearer ${__ENV.PESAGUARD_API_KEY}`;\n";
        return """
                import http from 'k6/http';
                import { check } from 'k6';

                const endpoint = %s;
                const method = %s;
                const requestBody = %s;
                const configuredHeaders = %s;
                const stages = %s;

                export const options = {
                  scenarios: {
                    load: {
                      executor: 'ramping-vus',
                      startVUs: 0,
                      stages,
                      gracefulRampDown: '30s',
                    },
                  },
                  thresholds: %s,
                  tags: { load_test_id: %s },
                };

                export default function () {
                  const headers = { ...configuredHeaders, 'Content-Type': %s };
                %s  const response = http.request(method, endpoint, requestBody, {
                    headers,
                    tags: { load_test_id: %s },
                  });
                  check(response, {
                    'response is not a server error': (r) => r.status < 500,
                  });
                }
                """.formatted(url, method, body, headers, stages, thresholds,
                literal(safeRunId), literal(test.getContentType()), keyHeader, literal(safeRunId));
    }

    private Map<String, List<String>> k6Thresholds(List<LoadTestThreshold> configured) {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        for (LoadTestThreshold threshold : configured) {
            String metric = threshold.metric();
            String operator = threshold.operator();
            if (metric.equals("p95Ms")) metric = "http_req_duration";
            if (metric.equals("p99Ms")) metric = "http_req_duration";
            String expression = switch (threshold.metric()) {
                case "p95Ms" -> "p(95)" + operator + threshold.value();
                case "p99Ms" -> "p(99)" + operator + threshold.value();
                default -> operator + threshold.value();
            };
            result.computeIfAbsent(metric, ignored -> new ArrayList<>()).add(expression);
        }
        return result;
    }

    private String literal(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Could not serialize load-test configuration.", exception);
        }
    }
}
