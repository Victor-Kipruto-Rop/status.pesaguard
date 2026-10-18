package com.pesaguard.backend.loadtest.application;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.loadtest.api.CreateLoadTestRequest;
import com.pesaguard.backend.loadtest.domain.LoadTestStage;

@Component
public class LoadTestRequestValidator {
    private static final Pattern SAFE_PATH = Pattern.compile("^/(?!/)[A-Za-z0-9_./-]{1,1023}$");
    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}$");
    private static final Set<String> BLOCKED_PATH_PARTS = Set.of(
            "payment", "payout", "mpesa", "transaction", "transfer", "charge", "refund",
            "sms", "email", "notification", "delete", "deactivate", "reset", "withdraw");
    private static final Set<String> BLOCKED_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "host", "content-length", "connection",
            "transfer-encoding", "proxy-authorization", "x-api-key");

    public void validate(CreateLoadTestRequest request, int maxVus, int maxRps,
            int maxDurationSeconds) {
        if (request.targetVus() > maxVus || request.maximumDurationSeconds() > maxDurationSeconds
                || request.targetRps() != null && request.targetRps() > maxRps
                || request.maximumRps() != null && request.maximumRps() > maxRps) {
            throw bad("LOAD_TEST_LIMIT_EXCEEDED", "The requested load exceeds the configured safety limits.");
        }
        if (request.targetRps() != null && request.maximumRps() != null
                && request.targetRps() > request.maximumRps()) {
            throw bad("LOAD_TEST_RPS_INVALID", "Target RPS cannot exceed maximum RPS.");
        }
        validatePath(request.endpointPath());
        validateHeaders(request.headers());
        long stageDuration = 0;
        int maxStageVus = 0;
        for (LoadTestStage stage : request.stages()) {
            if (stage == null || stage.durationSeconds() < 1 || stage.targetVus() < 0) {
                throw bad("LOAD_TEST_STAGE_INVALID", "Every stage must have a positive duration and nonnegative VUs.");
            }
            stageDuration += stage.durationSeconds();
            maxStageVus = Math.max(maxStageVus, stage.targetVus());
        }
        if (stageDuration > request.maximumDurationSeconds()) {
            throw bad("LOAD_TEST_DURATION_EXCEEDED",
                    "The total stage duration cannot exceed the configured maximum duration.");
        }
        if (maxStageVus > request.targetVus()) {
            throw bad("LOAD_TEST_VU_PROFILE_INVALID", "A stage cannot exceed the configured VU target.");
        }
        for (var threshold : request.thresholds()) {
            if (!Set.of("http_req_failed", "http_req_duration", "p95Ms", "p99Ms",
                    "errorRate", "sustainedRps", "peakRps", "peakVus").contains(threshold.metric())
                    || !Set.of("<", "<=", ">", ">=").contains(threshold.operator())) {
                throw bad("LOAD_TEST_THRESHOLD_INVALID", "The threshold metric or operator is not supported.");
            }
        }
    }

    public void validatePath(String path) {
        if (path == null || !SAFE_PATH.matcher(path).matches()
                || path.contains("..") || path.contains("//") || path.contains("\\")
                || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                || path.toLowerCase(Locale.ROOT).contains("%2e")
                || path.toLowerCase(Locale.ROOT).contains("%2f")
                || path.toLowerCase(Locale.ROOT).contains("%5c")) {
            throw bad("LOAD_TEST_TARGET_PATH_INVALID", "Target must be a safe path on the selected environment host.");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (BLOCKED_PATH_PARTS.stream().anyMatch(lower::contains)) {
            throw bad("LOAD_TEST_TARGET_BLOCKED", "This route may trigger a financial, external, or destructive action.");
        }
    }

    private void validateHeaders(Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String key = header.getKey();
            String value = header.getValue();
            if (key == null || value == null || !HEADER_NAME.matcher(key).matches()
                    || BLOCKED_HEADERS.contains(key.toLowerCase(Locale.ROOT))
                    || value.contains("\r") || value.contains("\n")) {
                throw bad("LOAD_TEST_HEADER_INVALID", "Headers cannot include credentials, routing controls, or invalid values.");
            }
        }
    }

    private static BusinessException bad(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }
}
