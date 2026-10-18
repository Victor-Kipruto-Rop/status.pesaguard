package com.pesaguard.backend.sandbox.api;

import com.pesaguard.backend.sandbox.domain.SandboxExecutionKind;

/**
 * Fixed, local sandbox scenarios. No scenario accepts a caller-controlled target.
 */
public enum SandboxScenario {
    SUCCESSFUL_PAYMENT(SandboxExecutionKind.TEST_REQUEST, "POST", "/v1/transactions", 201),
    FAILED_PAYMENT(SandboxExecutionKind.ERROR_RESPONSE, "POST", "/v1/transactions", 402),
    TIMEOUT(SandboxExecutionKind.TEST_REQUEST, "POST", "/v1/transactions", 504),
    DUPLICATE_REQUEST(SandboxExecutionKind.ERROR_RESPONSE, "POST", "/v1/transactions", 409),
    INVALID_CREDENTIAL(SandboxExecutionKind.AUTHENTICATION, "GET", "/v1/accounts", 401),
    INSUFFICIENT_FUNDS(SandboxExecutionKind.ERROR_RESPONSE, "POST", "/v1/transactions", 402),
    WEBHOOK_FAILURE(SandboxExecutionKind.WEBHOOK, "POST", "/webhooks/test", 502),
    NETWORK_FAILURE(SandboxExecutionKind.TEST_REQUEST, "POST", "/v1/transactions", 503),
    TRANSACTION_REVERSAL(SandboxExecutionKind.TEST_REQUEST, "POST",
            "/v1/transactions/{transaction_id}/reverse", 200);

    private final SandboxExecutionKind kind;
    private final String method;
    private final String path;
    private final int statusCode;

    SandboxScenario(SandboxExecutionKind kind, String method, String path, int statusCode) {
        this.kind = kind;
        this.method = method;
        this.path = path;
        this.statusCode = statusCode;
    }

    public SandboxExecutionKind kind() {
        return kind;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public int statusCode() {
        return statusCode;
    }

    public String resultBody(int amount, String currency) {
        String reference = "sbx_" + name().toLowerCase();
        if (statusCode >= 400) {
            String code = switch (this) {
                case FAILED_PAYMENT -> "payment_failed";
                case TIMEOUT -> "gateway_timeout";
                case DUPLICATE_REQUEST -> "duplicate_request";
                case INVALID_CREDENTIAL -> "invalid_credential";
                case INSUFFICIENT_FUNDS -> "insufficient_funds";
                case WEBHOOK_FAILURE -> "webhook_delivery_failed";
                case NETWORK_FAILURE -> "upstream_unavailable";
                default -> "simulation_error";
            };
            return "{\"scenario\":\"" + name() + "\",\"error\":{\"code\":\"" + code
                    + "\",\"message\":\"Simulated sandbox response\"},\"request_id\":\""
                    + reference + "\"}";
        }
        return "{\"scenario\":\"" + name() + "\",\"data\":{\"id\":\"" + reference
                + "\",\"status\":\"" + (this == TRANSACTION_REVERSAL ? "reversed" : "accepted")
                + "\",\"amount\":" + amount + ",\"currency\":\"" + currency
                + "\",\"test\":true},\"request_id\":\"" + reference + "\"}";
    }
}
