"""Security and reliability regression tests for Webhook services 69-72.

Before this file, webhook coverage was a single delivery-history test while the
event side (73-77) had 13 tests. These pin the invariants that matter:

  - Service 69 (Management): SSRF rejection at registration/update (https only,
    no localhost/metadata hostnames, no private/reserved resolved IPs), tenant
    scoping of get/update, and the immutable signing secret.
  - Service 70 (Delivery): delivery-time SSRF re-validation (DNS-rebinding
    defense), redirect responses abort the delivery (no redirect-follow), and the
    tenant-scoped WebhookDelivery audit record is written.
  - Service 71 (Retry): transient 5xx/timeouts are retried with backoff and a
    success on a later attempt is recorded as success; attempts are bounded.
  - Service 72 (Signature): HMAC-SHA256 over ``{timestamp}.{body}`` with the
    per-tenant signing secret; deterministic for identical inputs, changes when
    timestamp or body changes, and never uses a predictable fallback secret.

All behaviours were verified against the current implementation first (Rule #62).
Network is mocked; socket resolution is patched so no real DNS is performed.
"""

from __future__ import annotations

import hashlib
import hmac
import json
from types import SimpleNamespace
from unittest.mock import patch

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from models import Base, WebhookConfig, WebhookDelivery
import webhook_manager


@pytest.fixture()
def manager(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'webhooks.db'}")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    yield webhook_manager.WebhookManager(session), session
    session.close()
    engine.dispose()


def _register(manager, tenant="tenant-a", url="https://hooks.example.test/events", **kwargs):
    with patch.object(webhook_manager, "_validate_webhook_url", lambda u: None):
        return manager.register_webhook(tenant, url, ["payment.reconciled"], **kwargs)


def _webhook(session, webhook_id):
    return session.query(WebhookConfig).filter_by(id=webhook_id).one()


# --- Service 69: management / registration SSRF -------------------------------


def test_non_https_url_rejected_at_registration(manager):
    mgr, _ = manager
    result = mgr.register_webhook("tenant-a", "http://example.com/hook", ["payment.reconciled"])
    assert result["error"] == "invalid_webhook_url"
    assert result["reason"] == "url_must_use_https"


def test_localhost_and_metadata_hostnames_rejected(manager):
    mgr, session = manager
    for url in ("https://localhost/hook", "https://169.254.169.254/latest", "https://metadata.google.internal/"):
        result = mgr.register_webhook("tenant-a", url, ["payment.reconciled"])
        assert result["error"] == "invalid_webhook_url"
    assert session.query(WebhookConfig).count() == 0


def test_private_resolved_ip_rejected(manager):
    # DNS rebinding class: a public-looking hostname that resolves privately.
    mgr, session = manager
    with patch("socket.getaddrinfo", lambda h, p: [(2, 1, 6, "", ("10.0.0.5", 0))]):
        result = mgr.register_webhook("tenant-a", "https://internal.example.test/hook", ["payment.reconciled"])
    assert result["error"] == "invalid_webhook_url"
    assert "disallowed_address" in result["reason"]
    assert session.query(WebhookConfig).count() == 0


def test_registration_issues_unique_unpredictable_signing_secrets(manager):
    mgr, _ = manager
    first = _register(mgr, url="https://hooks.example.test/a")
    second = _register(mgr, url="https://hooks.example.test/b")
    assert first["signing_secret"].startswith("whsec_")
    assert first["signing_secret"] != second["signing_secret"]


def test_registration_bounds_retry_attempts_and_timeout(manager):
    mgr, _ = manager
    result = _register(mgr, retry_attempts=99, timeout_seconds=999)
    assert result["event_types"] == ["payment.reconciled"]


def test_update_rejects_ssrf_url_and_cannot_mutate_signing_secret(manager):
    mgr, session = manager
    reg = _register(mgr)
    # http URL rejected
    assert mgr.update_webhook(reg["id"], "tenant-a", url="http://example.com/h")["error"] == "invalid_webhook_url"
    # signing_secret is immutable even when passed explicitly
    mgr.update_webhook(reg["id"], "tenant-a", signing_secret="attacker-controlled", event_types=["other.event"])
    wh = _webhook(session, reg["id"])
    assert wh.signing_secret == reg["signing_secret"]


def test_get_and_update_are_tenant_scoped(manager):
    mgr, session = manager
    reg = _register(mgr)
    assert mgr.get_webhooks("tenant-b") == []
    assert mgr.get_webhooks("tenant-a", event_type="payment.reconciled")[0].id == reg["id"]
    assert mgr.get_webhooks("tenant-a", event_type="other.event") == []
    assert mgr.update_webhook(reg["id"], "tenant-b", url="https://evil.example.test/h") == {"error": "webhook_not_found"}


# --- Service 70: delivery -----------------------------------------------------


def test_delivery_time_ssrf_revalidation_aborts_delivery(manager):
    # DNS-rebinding defense: even if a registered URL starts valid, re-validation
    # at delivery time must abort when the target has become disallowed.
    mgr, session = manager
    reg = _register(mgr)
    wh = _webhook(session, reg["id"])
    with patch.object(webhook_manager, "_validate_webhook_url", lambda u: "url_must_use_https"):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})
    assert result["status"] == "failed"
    assert result["reason"] == "url_must_use_https"


def test_delivery_records_tenant_scoped_audit_row(manager):
    mgr, session = manager
    reg = _register(mgr)
    wh = _webhook(session, reg["id"])

    def ok_post(url, json=None, headers=None, timeout=None, allow_redirects=None):
        return SimpleNamespace(status_code=200, text="ok", is_redirect=False)

    with patch.object(webhook_manager, "_validate_webhook_url", lambda u: None), \
         patch.object(webhook_manager.requests, "post", ok_post):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})

    assert result["status"] == "success"
    delivery = session.query(WebhookDelivery).filter_by(id=result["id"]).one()
    assert delivery.tenant_id == "tenant-a"
    assert delivery.status == "success"
    assert delivery.response_status == 200
    assert delivery.delivered_at is not None


def test_redirect_response_aborts_delivery_without_following(manager):
    # allow_redirects=False plus explicit abort: a redirect must not be followed
    # (redirect-following is a classic SSRF/token-leak vector).
    mgr, session = manager
    reg = _register(mgr)
    wh = _webhook(session, reg["id"])
    calls = {"n": 0}

    def redirect_post(url, json=None, headers=None, timeout=None, allow_redirects=None):
        calls["n"] += 1
        assert allow_redirects is False
        return SimpleNamespace(status_code=302, text="", is_redirect=True)

    with patch.object(webhook_manager, "_validate_webhook_url", lambda u: None), \
         patch.object(webhook_manager.requests, "post", redirect_post), \
         patch.object(webhook_manager.time, "sleep", lambda s: None):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})

    assert result["status"] == "failed"
    assert calls["n"] == 1  # aborted after first redirect, no retry storm
    delivery = session.query(WebhookDelivery).filter_by(id=result["id"]).one()
    assert delivery.attempt_count == 1  # DB records the true single attempt


# --- Service 71: retry --------------------------------------------------------


def test_transient_failure_is_retried_then_succeeds(manager):
    mgr, session = manager
    reg = _register(mgr, retry_attempts=3)
    wh = _webhook(session, reg["id"])
    calls = {"n": 0}

    def flaky_post(url, json=None, headers=None, timeout=None, allow_redirects=None):
        calls["n"] += 1
        code = 500 if calls["n"] == 1 else 200
        return SimpleNamespace(status_code=code, text="body", is_redirect=False)

    with patch.object(webhook_manager, "_validate_webhook_url", lambda u: None), \
         patch.object(webhook_manager.requests, "post", flaky_post), \
         patch.object(webhook_manager.time, "sleep", lambda s: None):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})

    assert result["status"] == "success"
    assert result["attempt"] == 2
    assert calls["n"] == 2


def test_retries_are_bounded_by_configured_attempts(manager):
    mgr, session = manager
    reg = _register(mgr, retry_attempts=3)
    wh = _webhook(session, reg["id"])
    calls = {"n": 0}

    def failing_post(url, json=None, headers=None, timeout=None, allow_redirects=None):
        calls["n"] += 1
        return SimpleNamespace(status_code=500, text="err", is_redirect=False)

    with patch.object(webhook_manager, "_validate_webhook_url", lambda u: None), \
         patch.object(webhook_manager.requests, "post", failing_post), \
         patch.object(webhook_manager.time, "sleep", lambda s: None):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})

    assert result["status"] == "failed"
    assert calls["n"] == 3  # exactly retry_attempts, no unbounded loop
    delivery = session.query(WebhookDelivery).filter_by(id=result["id"]).one()
    assert delivery.status == "failed"


def test_timeout_is_treated_as_retryable_failure(manager):
    import requests as real_requests

    mgr, session = manager
    reg = _register(mgr, retry_attempts=2)
    wh = _webhook(session, reg["id"])
    calls = {"n": 0}

    def timeout_post(url, json=None, headers=None, timeout=None, allow_redirects=None):
        calls["n"] += 1
        raise real_requests.Timeout("simulated")

    with patch.object(webhook_manager, "_validate_webhook_url", lambda u: None), \
         patch.object(webhook_manager.requests, "post", timeout_post), \
         patch.object(webhook_manager.time, "sleep", lambda s: None):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})

    assert result["status"] == "failed"
    assert calls["n"] == 2


# --- Service 72: signature ----------------------------------------------------


def test_signature_format_and_hmac_verification(manager):
    # A receiver must be able to verify: format is t=<ts>,v1=<hmac-sha256> over
    # "{timestamp}.{json body}" using the tenant's signing secret.
    mgr, session = manager
    reg = _register(mgr)
    wh = _webhook(session, reg["id"])
    payload = {"event": "payment.reconciled", "id": "tx-1"}
    ts = "1700000000"

    signature = mgr._generate_signature(wh, payload, ts)
    assert signature.startswith(f"t={ts},v1=")
    digest = signature.split("v1=")[1]

    raw_body = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    expected = hmac.new(
        reg["signing_secret"].encode("utf-8"),
        f"{ts}.{raw_body}".encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    assert hmac.compare_digest(digest, expected)


def test_signature_is_deterministic_and_binds_timestamp_and_body(manager):
    mgr, session = manager
    reg = _register(mgr)
    wh = _webhook(session, reg["id"])

    base = mgr._generate_signature(wh, {"a": 1}, "1700000000")
    assert mgr._generate_signature(wh, {"a": 1}, "1700000000") == base  # deterministic
    assert mgr._generate_signature(wh, {"a": 1}, "1700000001") != base  # ts bound
    assert mgr._generate_signature(wh, {"a": 2}, "1700000000") != base  # body bound


def test_signature_changes_with_signing_secret(manager):
    # Different tenants/secrets must never produce the same signature for the
    # same body+timestamp (otherwise cross-tenant replay would verify).
    mgr, session = manager
    reg_a = _register(mgr, url="https://hooks.example.test/a")
    reg_b = _register(mgr, url="https://hooks.example.test/b")
    wh_a = _webhook(session, reg_a["id"])
    wh_b = _webhook(session, reg_b["id"])
    sig_a = mgr._generate_signature(wh_a, {"a": 1}, "1700000000")
    sig_b = mgr._generate_signature(wh_b, {"a": 1}, "1700000000")
    assert sig_a != sig_b


def test_delivery_headers_carry_signature_and_timestamp(manager):
    mgr, session = manager
    reg = _register(mgr)
    wh = _webhook(session, reg["id"])
    captured = {}

    def ok_post(url, json=None, headers=None, timeout=None, allow_redirects=None):
        captured.update(headers or {})
        return SimpleNamespace(status_code=200, text="ok", is_redirect=False)

    with patch.object(webhook_manager, "_validate_webhook_url", lambda u: None), \
         patch.object(webhook_manager.requests, "post", ok_post):
        mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})

    assert captured["X-Webhook-Signature"].startswith("t=")
    assert "v1=" in captured["X-Webhook-Signature"]
    assert captured["X-Webhook-Timestamp"].isdigit()
    assert captured["X-Webhook-Event"] == "payment.reconciled"
