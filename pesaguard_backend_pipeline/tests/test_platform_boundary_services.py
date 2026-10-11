"""Platform infrastructure boundary tests (124-138).

Services 124-138 (configuration, feature flags, health/readiness/liveness,
version, request context, request-id/correlation, rate limiting, caching,
distributed lock, observability, metrics, tracing, logging) already have broad
coverage: ``test_platform_infrastructure_services.py`` (9 tests: config
validation, request-context bounds, cache isolation/bounds/JSON-only, limiter
and lock validation, feature-flag kill switch, version sanitization),
``test_control_plane_health.py`` (4 tests: liveness without probes, readiness
sanitization and status mapping), ``test_security_event_instrumentation.py``
(rate-limit fail-closed and 503 paths), ``test_phase5_observability.py`` and
``test_phase6_observability_runtime.py`` (metrics/telemetry), and
``test_observability_otel.py`` (W3C traceparent).

This suite closes the remaining verified boundary gaps (each behaviour was
probed against the running implementation first -- Rule #62):

  - HTTP response must not echo an invalid ``X-Request-ID`` (too long or
    containing characters outside ``[A-Za-z0-9._:-]{1,128}``) back to the
    client; a valid one is echoed unchanged (services 130/131).
  - Observability context must not leak between sequential requests (services
    130/131/135/138).
  - Rate-limit buckets are isolated per client and the client identifier
    composes the authenticated tenant, so tenant-a traffic cannot exhaust
    tenant-b's bucket (service 132).
  - ``JsonFormatter`` emits single-line JSON even for messages containing
    newlines -- log forging cannot split the envelope (service 138).
  - Feature-flag tenant overrides are isolated per tenant; unknown flags and
    non-boolean values are rejected (service 125).

No production code is changed here.
"""

from __future__ import annotations

import json
import logging

import flask
import pytest
from flask import g

from feature_flags import FeatureFlagService
from logging_utils import JsonFormatter, clear_observability_context
from rate_limiter import TokenBucketRateLimiter, _get_client_identifier


class FakeTenantStore(dict):
    """TenantSettingsStore-compatible in-memory store (key, value signature)."""

    def update(self, key, value):  # noqa: A003 - mirrors TenantSettingsStore API
        dict.__setitem__(self, key, value)

    def get(self, key, default=None):  # noqa: A002
        return dict.get(self, key, default)


# --- Services 130/131: Request context & request-id/correlation -----------------


def test_response_does_not_echo_invalid_request_id():
    # An invalid-but-sendable X-Request-ID (>128 chars) must not be reflected;
    # the service replaces it with a generated UUID.
    from pesaguard_backend_pipeline.api.dashboard_app import app as dashboard_app

    client = dashboard_app.test_client()
    response = client.get("/version", headers={"X-Request-ID": "A" * 200})
    echoed = response.headers.get("X-Request-ID") or ""
    assert echoed != "A" * 200
    assert len(echoed) <= 128


def test_response_does_not_echo_request_id_with_illegal_characters():
    from pesaguard_backend_pipeline.api.dashboard_app import app as dashboard_app

    client = dashboard_app.test_client()
    supplied = "bad value!#$%"
    response = client.get("/version", headers={"X-Request-ID": supplied})
    echoed = response.headers.get("X-Request-ID") or ""
    assert echoed != supplied
    assert "!" not in echoed


def test_response_echoes_valid_request_id_unchanged():
    from pesaguard_backend_pipeline.api.dashboard_app import app as dashboard_app

    client = dashboard_app.test_client()
    response = client.get("/version", headers={"X-Request-ID": "req-ok.1234:abcd"})
    assert response.headers.get("X-Request-ID") == "req-ok.1234:abcd"


def test_observability_context_does_not_leak_between_requests():
    # The first request binds tenant/trace context; teardown must clear it so
    # the second, headerless request cannot observe the previous request's IDs.
    from pesaguard_backend_pipeline.api.dashboard_app import app as dashboard_app

    client = dashboard_app.test_client()
    first = client.get(
        "/version",
        headers={"X-Request-ID": "req-prev-1", "X-Correlation-ID": "corr-prev-1"},
    )
    assert first.headers.get("X-Request-ID") == "req-prev-1"

    second = client.get("/version")  # no correlation headers at all
    assert second.headers.get("X-Request-ID") != "req-prev-1"
    assert second.headers.get("X-Correlation-ID") != "corr-prev-1"


def test_correlation_id_defaults_to_request_id_when_header_absent():
    from pesaguard_backend_pipeline.api.dashboard_app import app as dashboard_app

    client = dashboard_app.test_client()
    response = client.get("/version", headers={"X-Request-ID": "corr-default-9"})
    # No X-Correlation-ID supplied -> the correlation ID falls back to the
    # (validated) request ID, keeping one traceable chain.
    assert response.headers.get("X-Correlation-ID") == "corr-default-9"


# --- Service 132: Rate Limiting Service ----------------------------------------


def test_rate_limit_buckets_are_isolated_per_client():
    limiter = TokenBucketRateLimiter(default_max_per_minute=1)
    assert limiter.is_allowed("tenant_a_client", "/endpoint")[0] is True
    # Same client exhausts its own bucket...
    assert limiter.is_allowed("tenant_a_client", "/endpoint")[0] is False
    # ...but a different client's bucket is untouched.
    assert limiter.is_allowed("tenant_b_client", "/endpoint")[0] is True


def test_rate_limit_buckets_are_isolated_per_endpoint():
    limiter = TokenBucketRateLimiter(default_max_per_minute=1)
    assert limiter.is_allowed("client-1", "/route-a")[0] is True
    assert limiter.is_allowed("client-1", "/route-a")[0] is False
    # Exhausting one endpoint must not block the same client elsewhere.
    assert limiter.is_allowed("client-1", "/route-b")[0] is True


def test_client_identifier_composes_tenant_for_bucket_isolation():
    # The authenticated tenant is part of the rate-limit key, so two tenants
    # sharing an IP still receive independent buckets (tenant-a cannot exhaust
    # tenant-b's allowance).
    app = flask.Flask("identifier-probe")
    with app.test_request_context("/x", environ_base={"REMOTE_ADDR": "1.2.3.4"}):
        g.tenant_id = "tenant-a"
        identifier_a = _get_client_identifier()
        g.tenant_id = "tenant-b"
        identifier_b = _get_client_identifier()
    assert identifier_a != identifier_b
    assert "tenant-a" in identifier_a
    assert "tenant-b" in identifier_b


# --- Service 125: Feature Flag Service -----------------------------------------


def test_feature_flag_tenant_overrides_are_isolated():
    service = FeatureFlagService(tenant_store=FakeTenantStore())
    service.set_tenant_override("tenant-a", "beta_dashboard", True)

    assert service.resolve("tenant-a", "beta_dashboard") is True
    # tenant-b and the default tenant must not observe tenant-a's override.
    assert service.resolve("tenant-b", "beta_dashboard") is False
    assert service.resolve("default", "beta_dashboard") is False


def test_feature_flag_rejects_unknown_flag():
    service = FeatureFlagService(tenant_store=FakeTenantStore())
    with pytest.raises(ValueError, match="unknown feature flag"):
        service.set_tenant_override("tenant-a", "nonexistent_flag", True)


def test_feature_flag_rejects_non_boolean_override():
    service = FeatureFlagService(tenant_store=FakeTenantStore())
    with pytest.raises(ValueError, match="must be a boolean"):
        service.set_tenant_override("tenant-a", "alerting", "yes-i-guess")


# --- Service 138: Logging Service ----------------------------------------------


def test_json_formatter_emits_single_line_even_for_multiline_message():
    # Log forging: a message containing newlines must not split the JSON
    # envelope into multiple log lines. json.dumps escapes them.
    clear_observability_context()
    record = logging.LogRecord(
        "pesaguard.probe", logging.INFO, __file__, 1,
        "line1\nline2 FAKE LOG", (), None,
    )
    output = JsonFormatter().format(record)
    assert "\n" not in output
    parsed = json.loads(output)
    assert parsed["message"] == "line1\nline2 FAKE LOG"
    assert parsed["level"] == "INFO"


def test_json_formatter_generates_correlation_id_when_context_is_empty():
    clear_observability_context()
    record = logging.LogRecord(
        "pesaguard.probe", logging.INFO, __file__, 1, "no context", (), None,
    )
    parsed = json.loads(JsonFormatter().format(record))
    assert parsed["correlation_id"]
    assert len(parsed["correlation_id"]) == 8  # uuid4 truncated


def test_json_formatter_only_emits_whitelisted_observability_fields():
    clear_observability_context()
    from logging_utils import bind_observability_context

    bind_observability_context(tenant_id="tenant-a", correlation_id="corr-x")
    # An unlisted key must never reach the log envelope.
    bind_observability_context(not_a_field="should-not-appear")
    record = logging.LogRecord(
        "pesaguard.probe", logging.INFO, __file__, 1, "scoped", (), None,
    )
    parsed = json.loads(JsonFormatter().format(record))
    assert parsed["tenant_id"] == "tenant-a"
    assert parsed["correlation_id"] == "corr-x"
    assert "not_a_field" not in parsed
    clear_observability_context()
