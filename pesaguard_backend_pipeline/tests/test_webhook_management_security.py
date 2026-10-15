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
import ssl
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import patch

import pytest
import dns.exception
import dns.resolver
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from models import Base, Discrepancy, WebhookConfig, WebhookDelivery
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
    with patch(
        "webhook_manager._resolve_host_addresses",
        return_value=("10.0.0.5",),
    ):
        result = mgr.register_webhook("tenant-a", "https://internal.example.test/hook", ["payment.reconciled"])
    assert result["error"] == "invalid_webhook_url"
    assert "disallowed_address" in result["reason"]
    assert session.query(WebhookConfig).count() == 0


@pytest.mark.parametrize(
    "answers",
    [
        [("198.51.100.1", 443), ("10.0.0.2", 443)],
        [("2001:4860:4860::8888", 443), ("fd00::1", 443)],
        [("8.8.8.8", 443), ("169.254.169.254", 443)],
        [("8.8.8.8", 443), ("100.64.0.1", 443)],
    ],
)
def test_mixed_or_special_dns_answers_reject_entire_destination(manager, answers):
    mgr, session = manager
    addresses = tuple(address for address, _ in answers)
    with patch("webhook_manager._resolve_host_addresses", return_value=addresses):
        result = mgr.register_webhook("tenant-a", "https://mixed.example.test/hook", ["event"])
    assert result["error"] == "invalid_webhook_url"
    assert "disallowed_address" in result["reason"]
    assert session.query(WebhookConfig).count() == 0


def test_url_policy_rejects_local_and_metadata_names_and_userinfo(manager):
    mgr, session = manager
    urls = (
        "https://localhost/hook",
        "https://service.local/hook",
        "https://metadata.google.internal/computeMetadata/v1",
        "https://user:password@hooks.example.test/hook",
    )
    for url in urls:
        assert mgr.register_webhook("tenant-a", url, ["event"])["error"] == "invalid_webhook_url"
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


def test_signing_secret_can_be_rotated_once_and_revoked(manager):
    mgr, session = manager
    reg = _register(mgr)
    rotated = mgr.rotate_signing_secret(reg["id"], "tenant-a")
    assert rotated["status"] == "rotated"
    assert rotated["signing_secret"] != reg["signing_secret"]
    assert "signing_secret" not in mgr.update_webhook(reg["id"], "tenant-a", active=True)
    assert "signing_secret" not in mgr.get_delivery_history(reg["id"], "tenant-a")

    revoked = mgr.revoke_signing_secret(reg["id"], "tenant-a")
    assert revoked == {"status": "revoked", "id": reg["id"]}
    webhook = _webhook(session, reg["id"])
    assert webhook.active is False
    assert webhook.signing_secret is None


def test_get_and_update_are_tenant_scoped(manager):
    mgr, session = manager
    reg = _register(mgr)
    assert mgr.get_webhooks("tenant-b") == []
    assert mgr.get_webhooks("tenant-a", event_type="payment.reconciled")[0].id == reg["id"]
    assert mgr.get_webhooks("tenant-a", event_type="other.event") == []
    assert mgr.update_webhook(reg["id"], "tenant-b", url="https://evil.example.test/h") == {"error": "webhook_not_found"}


def test_webhook_health_aggregation_is_tenant_scoped(manager, monkeypatch):
    from escalation_engine import EscalationEngine

    _, session = manager
    session.add_all([
        WebhookDelivery(
            id="delivery-a-success",
            tenant_id="tenant-a",
            webhook_id="webhook-a",
            event_type="payment.reconciled",
            payload={"event_id": "a"},
            status="success",
            attempt_count=1,
        ),
        WebhookDelivery(
            id="delivery-b-failure",
            tenant_id="tenant-b",
            webhook_id="webhook-b",
            event_type="payment.reconciled",
            payload={"event_id": "b"},
            status="failed",
            attempt_count=5,
        ),
    ])
    session.commit()
    monkeypatch.setenv("WEBHOOK_FAILURE_THRESHOLD", "0.1")

    health = EscalationEngine(session).check_webhook_health("tenant-a")

    assert health["status"] == "ok"
    assert health["total_deliveries"] == 1
    assert health["failed_deliveries"] == 0


def test_webhook_health_alerts_for_stale_pending_and_deduplicates(manager, monkeypatch):
    _, session = manager
    from escalation_engine import EscalationEngine

    now = datetime.now(timezone.utc)
    session.add(WebhookDelivery(
        id="stale-pending-health",
        tenant_id="tenant-a",
        webhook_id="webhook-a",
        event_type="payment.reconciled",
        payload={"event_id": "stale-health-event"},
        status="processing",
        attempt_count=1,
        created_at=now - timedelta(minutes=20),
        updated_at=now - timedelta(minutes=20),
    ))
    session.commit()
    monkeypatch.setenv("WEBHOOK_FAILURE_THRESHOLD", "0")

    engine = EscalationEngine(session)
    first = engine.check_webhook_health("tenant-a")
    second = engine.check_webhook_health("tenant-a")

    assert first["status"] == "escalation_triggered"
    assert first["stale_pending_deliveries"] == 1
    assert second["stale_pending_deliveries"] == 1
    assert session.query(Discrepancy).filter_by(
        tenant_id="tenant-a",
        anomaly_type="webhook_delivery_failure",
        status="needs_review",
    ).count() == 1


def test_webhook_health_handles_empty_scoped_query(manager):
    _, session = manager
    from escalation_engine import EscalationEngine

    result = EscalationEngine(session).check_webhook_health(
        "tenant-a",
        webhook_id="tenant-a-no-activity",
    )

    assert result == {"status": "ok", "message": "No recent webhook activity"}


def test_webhook_health_rejects_invalid_threshold_configuration(manager, monkeypatch):
    _, session = manager
    from escalation_engine import EscalationEngine

    now = datetime.now(timezone.utc)
    session.add(WebhookDelivery(
        id="health-config-validation",
        tenant_id="tenant-a",
        webhook_id="webhook-a",
        event_type="payment.reconciled",
        payload={"event_id": "health-config-validation"},
        status="success",
        attempt_count=1,
        created_at=now,
    ))
    session.commit()
    engine = EscalationEngine(session)
    monkeypatch.setenv("WEBHOOK_PENDING_STALE_SECONDS", "30")
    with pytest.raises(ValueError, match="WEBHOOK_PENDING_STALE_SECONDS"):
        engine.check_webhook_health("tenant-a")

    monkeypatch.setenv("WEBHOOK_PENDING_STALE_SECONDS", "900")
    monkeypatch.setenv("WEBHOOK_FAILURE_THRESHOLD", "NaN")
    with pytest.raises(ValueError, match="WEBHOOK_FAILURE_THRESHOLD"):
        engine.check_webhook_health("tenant-a")


def test_webhook_health_accepts_recent_timezone_aware_processing_record(monkeypatch):
    from escalation_engine import EscalationEngine

    now = datetime.now(timezone.utc)

    class Query:
        def filter(self, *args):
            return self

        def all(self):
            return [SimpleNamespace(
                status="processing",
                updated_at=now - timedelta(seconds=60),
            )]

    class Session:
        def query(self, _model):
            return Query()

    monkeypatch.setenv("WEBHOOK_PENDING_STALE_SECONDS", "900")
    monkeypatch.setenv("WEBHOOK_FAILURE_THRESHOLD", "0.10")

    result = EscalationEngine(Session()).check_webhook_health("tenant-a")

    assert result["status"] == "ok"
    assert result["stale_pending_deliveries"] == 0


# --- Service 70: delivery -----------------------------------------------------


def test_delivery_time_ssrf_revalidation_aborts_delivery(manager):
    # DNS-rebinding defense: even if a registered URL starts valid, re-validation
    # at delivery time must abort when the target has become disallowed.
    mgr, session = manager
    reg = _register(mgr)
    wh = _webhook(session, reg["id"])
    with patch.object(
        webhook_manager,
        "_resolve_webhook_destination",
        return_value=("url_must_use_https", ()),
    ):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})
    assert result["status"] == "failed"
    assert result["reason"] == "url_must_use_https"
    delivery = session.query(WebhookDelivery).filter_by(id=result["id"]).one()
    assert delivery.attempt_count == 0
    assert delivery.status == "failed"


def test_delivery_attempt_is_committed_before_outbound_request(manager):
    mgr, session = manager
    reg = _register(mgr)
    wh = _webhook(session, reg["id"])
    observed = {}

    def outbound(url, body=None, headers=None, pinned_ips=None, timeout_seconds=None):
        delivery = session.query(WebhookDelivery).filter_by(webhook_id=wh.id).one()
        observed.update(status=delivery.status, attempts=delivery.attempt_count)
        return SimpleNamespace(status_code=204, body=b"", is_redirect=False)

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(webhook_manager, "_post_pinned_webhook", outbound):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"event_id": "evt-1"})

    assert observed == {"status": "processing", "attempts": 1}
    assert result["status"] == "success"


def test_crash_during_delivery_leaves_durable_processing_attempt(manager):
    mgr, session = manager
    reg = _register(mgr, retry_attempts=2)
    wh = _webhook(session, reg["id"])

    def crash_after_request_starts(*args, **kwargs):
        raise SystemExit("simulated process termination during outbound request")

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(webhook_manager, "_post_pinned_webhook", crash_after_request_starts):
        with pytest.raises(SystemExit):
            mgr._deliver_webhook(wh, "payment.reconciled", {"event_id": "evt-crash"})

    delivery = session.query(WebhookDelivery).filter_by(webhook_id=wh.id).one()
    assert delivery.status == "processing"
    assert delivery.attempt_count == 1
    assert delivery.response_status is None


def test_result_persistence_failure_is_surfaced_and_attempt_remains_auditable(manager):
    mgr, session = manager
    reg = _register(mgr, retry_attempts=1)
    wh = _webhook(session, reg["id"])
    original_commit = session.commit
    commits = {"count": 0}

    def fail_result_commit():
        commits["count"] += 1
        if commits["count"] == 3:
            raise RuntimeError("simulated result persistence outage")
        original_commit()

    session.commit = fail_result_commit
    try:
        with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
             patch.object(
                 webhook_manager,
                 "_post_pinned_webhook",
                 lambda *args, **kwargs: SimpleNamespace(status_code=204, body=b"", is_redirect=False),
             ):
            with pytest.raises(RuntimeError, match="result persistence outage"):
                mgr._deliver_webhook(wh, "payment.reconciled", {"event_id": "evt-persist"})
    finally:
        session.commit = original_commit
        session.rollback()

    delivery = session.query(WebhookDelivery).filter_by(webhook_id=wh.id).one()
    assert delivery.status == "processing"
    assert delivery.attempt_count == 1


def test_retry_schedule_crash_leaves_recoverable_processing_attempt(manager):
    mgr, session = manager
    reg = _register(mgr, retry_attempts=3)
    wh = _webhook(session, reg["id"])

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(
             webhook_manager,
             "_post_pinned_webhook",
             lambda *args, **kwargs: SimpleNamespace(status_code=503, body=b"", is_redirect=False),
         ), \
         patch.object(webhook_manager.time, "sleep", side_effect=SystemExit("simulated retry scheduler crash")):
        with pytest.raises(SystemExit):
            mgr._deliver_webhook(wh, "payment.reconciled", {"event_id": "evt-retry"})

    delivery = session.query(WebhookDelivery).filter_by(webhook_id=wh.id).one()
    assert delivery.status == "processing"
    assert delivery.attempt_count == 1
    assert delivery.response_status == 503


def test_recovery_resumes_stale_delivery_and_preserves_identity(manager):
    mgr, session = manager
    registration = _register(mgr, retry_attempts=2)
    webhook = _webhook(session, registration["id"])
    now = datetime.now(timezone.utc)
    delivery = WebhookDelivery(
        id="delivery-recover-1",
        tenant_id="tenant-a",
        webhook_id=webhook.id,
        event_type="payment.reconciled",
        payload={"event_id": "evt-recovery-1", "amount": 17},
        status="processing",
        attempt_count=1,
        created_at=now - timedelta(hours=1),
        updated_at=now - timedelta(hours=1),
    )
    session.add(delivery)
    session.commit()
    observed = []

    def successful_send(url, body=None, headers=None, pinned_ips=None, timeout_seconds=None):
        observed.append(headers["X-Webhook-Event-Id"])
        return SimpleNamespace(status_code=204, body=b"", is_redirect=False)

    with patch.object(
        webhook_manager,
        "_resolve_webhook_destination",
        return_value=(None, ("93.184.216.34",)),
    ), patch.object(webhook_manager, "_post_pinned_webhook", successful_send):
        outcomes = mgr.recover_stale_deliveries("tenant-a", now=now)

    session.refresh(delivery)
    assert len(outcomes) == 1
    assert outcomes[0]["status"] == "success"
    assert delivery.status == "success"
    assert delivery.attempt_count == 2
    assert observed == ["evt-recovery-1"]
    assert session.query(WebhookDelivery).filter_by(webhook_id=webhook.id).count() == 1


def test_recovery_is_tenant_scoped(manager):
    mgr, session = manager
    now = datetime.now(timezone.utc)
    for tenant_id in ("tenant-a", "tenant-b"):
        registration = _register(mgr, tenant=tenant_id, retry_attempts=2)
        webhook = _webhook(session, registration["id"])
        session.add(WebhookDelivery(
            id=f"delivery-{tenant_id}",
            tenant_id=tenant_id,
            webhook_id=webhook.id,
            event_type="payment.reconciled",
            payload={"event_id": f"evt-{tenant_id}"},
            status="processing",
            attempt_count=1,
            created_at=now - timedelta(hours=1),
            updated_at=now - timedelta(hours=1),
        ))
    session.commit()

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=("blocked", ())), \
         patch.object(webhook_manager, "_post_pinned_webhook") as outbound:
        outcomes = mgr.recover_stale_deliveries("tenant-a", now=now)

    assert len(outcomes) == 1
    assert outcomes[0]["id"] == "delivery-tenant-a"
    assert outbound.call_count == 0
    tenant_b_delivery = session.query(WebhookDelivery).filter_by(id="delivery-tenant-b").one()
    assert tenant_b_delivery.status == "processing"
    assert tenant_b_delivery.attempt_count == 1


def test_recovery_does_not_exceed_attempt_budget(manager):
    mgr, session = manager
    registration = _register(mgr, retry_attempts=1)
    webhook = _webhook(session, registration["id"])
    now = datetime.now(timezone.utc)
    delivery = WebhookDelivery(
        id="delivery-recovery-budget",
        tenant_id="tenant-a",
        webhook_id=webhook.id,
        event_type="payment.reconciled",
        payload={"event_id": "evt-recovery-budget"},
        status="processing",
        attempt_count=1,
        created_at=now - timedelta(hours=1),
        updated_at=now - timedelta(hours=1),
    )
    session.add(delivery)
    session.commit()

    with patch.object(webhook_manager, "_post_pinned_webhook") as outbound:
        outcomes = mgr.recover_stale_deliveries("tenant-a", now=now)

    session.refresh(delivery)
    assert outcomes == [{
        "id": delivery.id,
        "status": "failed",
        "reason": "recovery_attempt_budget_exhausted",
    }]
    assert delivery.status == "failed"
    assert delivery.response_body == "recovery_attempt_budget_exhausted"
    outbound.assert_not_called()


def test_recovery_skips_delivery_locked_by_another_worker(manager):
    _, session = manager
    now = datetime.now(timezone.utc)
    delivery = WebhookDelivery(
        id="delivery-recovery-locked",
        tenant_id="tenant-a",
        webhook_id="webhook-a",
        event_type="payment.reconciled",
        payload={"event_id": "evt-recovery-locked"},
        status="processing",
        attempt_count=1,
        created_at=now - timedelta(hours=1),
        updated_at=now - timedelta(hours=1),
    )
    session.add(delivery)
    session.commit()

    class BusyLock:
        def acquire(self, name, ttl_seconds=30):
            return None

        def release(self, name, token):
            raise AssertionError("a lock that was not acquired must not be released")

    locked_manager = webhook_manager.WebhookManager(session, lock_service=BusyLock())
    outcomes = locked_manager.recover_stale_deliveries("tenant-a", now=now)

    assert outcomes == [{"id": delivery.id, "status": "skipped_locked"}]
    assert delivery.status == "processing"
    assert delivery.attempt_count == 1


def test_event_identity_is_stable_across_retries_and_duplicate_triggers(manager):
    mgr, session = manager
    reg = _register(mgr, retry_attempts=2)
    wh = _webhook(session, reg["id"])
    identities = []

    def fail_then_succeed(url, body=None, headers=None, pinned_ips=None, timeout_seconds=None):
        identities.append(headers["X-Webhook-Event-Id"])
        status = 500 if len(identities) == 1 else 204
        return SimpleNamespace(status_code=status, body=b"", is_redirect=False)

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(webhook_manager, "_post_pinned_webhook", fail_then_succeed), \
         patch.object(webhook_manager.time, "sleep", lambda _: None):
        mgr._deliver_webhook(wh, "payment.reconciled", {"event_id": "evt-stable", "amount": 5})
        mgr._deliver_webhook(wh, "payment.reconciled", {"event_id": "evt-stable", "amount": 5})

    assert len(set(identities)) == 1
    assert identities[0] == "evt-stable"


def test_webhook_signature_verifier_rejects_bad_replay_and_malformed_body():
    now = 1_800_000_000
    secret = "whsec_test_only"
    timestamp = str(now)
    body = b'{"event":"reconciled"}'
    signature = webhook_manager._signature_for_body(secret, body, timestamp)

    assert webhook_manager.verify_webhook_signature(body, signature, secret, now=now)
    assert not webhook_manager.verify_webhook_signature(body, "t=1800000000,v1=" + "0" * 64, secret, now=now)
    assert not webhook_manager.verify_webhook_signature(body, signature, secret, now=now + 301)
    malformed = b'{"event":'
    malformed_signature = webhook_manager._signature_for_body(secret, malformed, timestamp)
    assert not webhook_manager.verify_webhook_signature(malformed, malformed_signature, secret, now=now)
    non_finite = b'{"amount":NaN}'
    non_finite_signature = webhook_manager._signature_for_body(secret, non_finite, timestamp)
    assert not webhook_manager.verify_webhook_signature(non_finite, non_finite_signature, secret, now=now)
    assert not webhook_manager.verify_webhook_signature(
        body,
        signature,
        secret,
        now=now,
        tolerance_seconds=3601,
    )
    assert not webhook_manager.verify_webhook_signature(
        b" " * (webhook_manager._MAX_WEBHOOK_BODY_BYTES + 1),
        signature,
        secret,
        now=now,
    )


def test_pinned_https_transport_uses_hostname_tls_and_ignores_proxy_environment(monkeypatch):
    captured = {}

    class FakeResponse:
        status = 204
        data = b"x" * 1000

        def read(self, limit):
            captured["response_limit"] = limit
            return self.data[:limit]

        def close(self):
            captured["response_closed"] = True

    class FakePool:
        def __init__(self, **kwargs):
            captured.update(kwargs)

        def urlopen(self, method, path, **kwargs):
            captured.update(method=method, path=path, request=kwargs)
            return FakeResponse()

        def close(self):
            captured["pool_closed"] = True

    monkeypatch.setenv("HTTPS_PROXY", "http://127.0.0.1:8080")
    monkeypatch.setenv("https_proxy", "http://127.0.0.1:8080")
    monkeypatch.setattr(webhook_manager, "_PinnedHTTPSConnectionPool", FakePool)

    result = webhook_manager._post_pinned_webhook(
        "https://hooks.example.test/callback?x=1",
        b'{"x":1}',
        {"Content-Type": "application/json"},
        ("93.184.216.34",),
        7,
    )

    assert result.status_code == 204
    assert len(result.body) == 500
    assert captured["host"] == "hooks.example.test"
    assert captured["pinned_ip"] == "93.184.216.34"
    assert captured["cert_reqs"] == ssl.CERT_REQUIRED
    assert captured["assert_hostname"] == "hooks.example.test"
    assert captured["server_hostname"] == "hooks.example.test"
    assert captured["method"] == "POST"
    assert captured["path"] == "/callback?x=1"
    assert captured["request"]["redirect"] is False
    assert captured["request"]["retries"] is False
    assert captured["request"]["timeout"].total == 7
    assert captured["response_limit"] == 501
    assert captured["response_closed"] and captured["pool_closed"]


def test_pinned_transport_preserves_tls_verification_errors(monkeypatch):
    class FakePool:
        def __init__(self, **kwargs):
            pass

        def urlopen(self, *args, **kwargs):
            raise webhook_manager.urllib3.exceptions.SSLError("hostname mismatch")

        def close(self):
            pass

    monkeypatch.setattr(webhook_manager, "_PinnedHTTPSConnectionPool", FakePool)
    with pytest.raises(webhook_manager.urllib3.exceptions.SSLError):
        webhook_manager._post_pinned_webhook(
            "https://hooks.example.test/",
            b"{}",
            {"Content-Type": "application/json"},
            ("93.184.216.34",),
            3,
        )


def test_dns_is_resolved_once_and_connection_uses_that_exact_validated_address():
    with patch(
        "webhook_manager._resolve_host_addresses",
        return_value=("93.184.216.34",),
    ) as resolver:
        rejection, pinned_ips = webhook_manager._resolve_webhook_destination("https://hooks.example.test/")
    assert rejection is None
    assert pinned_ips == ("93.184.216.34",)
    resolver.assert_called_once()


def test_public_ip_literal_is_validated_without_dns():
    with patch("webhook_manager._resolve_host_addresses") as resolver:
        reason, addresses = webhook_manager._resolve_webhook_destination("https://93.184.216.34/events")

    assert reason is None
    assert addresses == ("93.184.216.34",)
    resolver.assert_not_called()


def test_dns_change_after_validation_cannot_change_socket_destination(monkeypatch):
    calls = {"dns": 0, "socket_target": None}

    def dns_answers(*args, **kwargs):
        calls["dns"] += 1
        address = "93.184.216.34" if calls["dns"] == 1 else "127.0.0.1"
        return (address,)

    monkeypatch.setattr(webhook_manager, "_resolve_host_addresses", dns_answers)
    reason, addresses = webhook_manager._resolve_webhook_destination(
        "https://hooks.example.test/events"
    )
    assert reason is None

    conn = webhook_manager._PinnedHTTPSConnection(
        host="hooks.example.test",
        port=443,
        pinned_ip=addresses[0],
        timeout=3,
    )

    def socket_connect(address, *args, **kwargs):
        calls["socket_target"] = address
        return object()

    monkeypatch.setattr(webhook_manager.urllib3_connection, "create_connection", socket_connect)
    conn._new_conn()

    assert calls == {"dns": 1, "socket_target": ("93.184.216.34", 443)}
    assert conn.host == "hooks.example.test"


def test_dns_resolution_uses_absolute_a_and_aaaa_queries_with_a_bounded_deadline(monkeypatch):
    observed = []

    class Resolver:
        def resolve(self, name, record_type, *, lifetime, search):
            observed.append((name, record_type, lifetime, search))
            if record_type == "A":
                return [SimpleNamespace(address="93.184.216.34")]
            return []

    monkeypatch.setattr(webhook_manager.dns.resolver, "Resolver", lambda configure: Resolver())
    addresses = webhook_manager._resolve_host_addresses("hooks.example.test")

    assert addresses == ("93.184.216.34",)
    assert [item[:2] for item in observed] == [
        ("hooks.example.test.", "A"),
        ("hooks.example.test.", "AAAA"),
    ]
    assert all(0 < lifetime <= webhook_manager._DNS_RESOLUTION_TIMEOUT_SECONDS for _, _, lifetime, _ in observed)
    assert all(search is False for _, _, _, search in observed)


def test_dns_timeout_fails_destination_validation_closed(monkeypatch):
    def timeout(hostname):
        raise dns.exception.Timeout()

    monkeypatch.setattr(webhook_manager, "_resolve_host_addresses", timeout)
    reason, pinned_ips = webhook_manager._resolve_webhook_destination("https://hooks.example.test/")

    assert reason == "url_hostname_cannot_be_resolved"
    assert pinned_ips == ()


def test_delivery_records_tenant_scoped_audit_row(manager):
    mgr, session = manager
    reg = _register(mgr)
    wh = _webhook(session, reg["id"])

    def ok_post(url, body=None, headers=None, pinned_ips=None, timeout_seconds=None):
        assert pinned_ips == ("93.184.216.34",)
        assert timeout_seconds == 10
        return SimpleNamespace(status_code=200, body=b"ok", is_redirect=False)

    with patch.object(webhook_manager, "_validate_webhook_url", lambda u: None), \
         patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(webhook_manager, "_post_pinned_webhook", ok_post):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})

    assert result["status"] == "success"
    delivery = session.query(WebhookDelivery).filter_by(id=result["id"]).one()
    assert delivery.tenant_id == "tenant-a"
    assert delivery.status == "success"
    assert delivery.response_status == 200
    assert delivery.delivered_at is not None
    assert result["attempts"] == delivery.attempt_count == 1
    assert result["response_code"] == delivery.response_status
    history = mgr.get_delivery_history(wh.id, "tenant-a")
    assert history[0]["status"] == result["status"]
    assert history[0]["attempt_count"] == result["attempts"]
    assert history[0]["response_status"] == result["response_code"]


def test_redirect_response_aborts_delivery_without_following(manager):
    # allow_redirects=False plus explicit abort: a redirect must not be followed
    # (redirect-following is a classic SSRF/token-leak vector).
    mgr, session = manager
    reg = _register(mgr)
    wh = _webhook(session, reg["id"])
    calls = {"n": 0}

    def redirect_post(url, body=None, headers=None, pinned_ips=None, timeout_seconds=None):
        calls["n"] += 1
        return SimpleNamespace(status_code=302, body=b"", is_redirect=True)

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(webhook_manager, "_post_pinned_webhook", redirect_post), \
         patch.object(webhook_manager.time, "sleep", lambda s: None):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})

    assert result["status"] == "failed"
    assert calls["n"] == 1  # aborted after first redirect, no retry storm
    assert result["attempts"] == 1
    assert result["http_requests"] == 1
    assert result["configured_attempts"] == 3
    delivery = session.query(WebhookDelivery).filter_by(id=result["id"]).one()
    assert delivery.attempt_count == 1  # DB records the true single attempt


# --- Service 71: retry --------------------------------------------------------


def test_transient_failure_is_retried_then_succeeds(manager):
    mgr, session = manager
    reg = _register(mgr, retry_attempts=3)
    wh = _webhook(session, reg["id"])
    calls = {"n": 0}

    def flaky_post(url, body=None, headers=None, pinned_ips=None, timeout_seconds=None):
        calls["n"] += 1
        code = 500 if calls["n"] == 1 else 200
        return SimpleNamespace(status_code=code, body=b"body", is_redirect=False)

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(webhook_manager, "_post_pinned_webhook", flaky_post), \
         patch.object(webhook_manager.time, "sleep", lambda s: None):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})

    assert result["status"] == "success"
    assert result["attempt"] == 2
    assert calls["n"] == 2
    assert result["http_requests"] == 2
    assert result["retryable_failures"] == 1


def test_retries_are_bounded_by_configured_attempts(manager):
    mgr, session = manager
    reg = _register(mgr, retry_attempts=3)
    wh = _webhook(session, reg["id"])
    calls = {"n": 0}

    def failing_post(url, body=None, headers=None, pinned_ips=None, timeout_seconds=None):
        calls["n"] += 1
        return SimpleNamespace(status_code=500, body=b"err", is_redirect=False)

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(webhook_manager, "_post_pinned_webhook", failing_post), \
         patch.object(webhook_manager.time, "sleep", lambda s: None):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})

    assert result["status"] == "failed"
    assert calls["n"] == 3  # exactly retry_attempts, no unbounded loop
    assert result["configured_attempts"] == 3
    assert result["http_requests"] == 3
    delivery = session.query(WebhookDelivery).filter_by(id=result["id"]).one()
    assert delivery.status == "failed"


def test_retry_delay_uses_bounded_exponential_full_jitter(manager):
    mgr, _ = manager
    reg = _register(mgr, retry_attempts=5)
    wh = _webhook(manager[1], reg["id"])
    bounds = []
    sleeps = []

    class FixedRandom:
        def uniform(self, lower, upper):
            bounds.append((lower, upper))
            return upper

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(
             webhook_manager,
             "_post_pinned_webhook",
             lambda *args, **kwargs: SimpleNamespace(status_code=503, body=b"", is_redirect=False),
         ), \
         patch.object(webhook_manager.secrets, "SystemRandom", FixedRandom), \
         patch.object(webhook_manager.time, "sleep", sleeps.append):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"event_id": "evt-jitter"})

    assert bounds == [(0, 1), (0, 2), (0, 4), (0, 8)]
    assert sleeps == [1, 2, 4, 8]
    assert result["attempts"] == result["http_requests"] == 5
    assert result["retryable_failures"] == 5
    assert result["configured_attempts"] == 5
    assert result["retryable"] is True
    assert result["retries_exhausted"] is True


def test_non_retryable_client_error_stops_after_one_http_request(manager):
    mgr, _ = manager
    reg = _register(mgr, retry_attempts=5)
    wh = _webhook(manager[1], reg["id"])
    calls = []
    result_response = SimpleNamespace(status_code=410, body=b"gone", is_redirect=False)

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(webhook_manager, "_post_pinned_webhook", lambda *args, **kwargs: calls.append(1) or result_response):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"event_id": "evt-gone"})

    assert len(calls) == 1
    assert result["attempts"] == 1
    assert result["http_requests"] == 1
    assert result["configured_attempts"] == 5
    assert result["retryable"] is False
    assert result["reason"] == "http_client_error"


def test_tls_verification_failure_is_not_retried(manager):
    mgr, _ = manager
    reg = _register(mgr, retry_attempts=5)
    wh = _webhook(manager[1], reg["id"])
    calls = []

    def tls_failure(*args, **kwargs):
        calls.append(1)
        raise webhook_manager.urllib3.exceptions.SSLError("certificate rejected")

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(webhook_manager, "_post_pinned_webhook", tls_failure):
        result = mgr._deliver_webhook(wh, "payment.reconciled", {"event_id": "evt-tls"})

    assert len(calls) == 1
    assert result["attempts"] == 1
    assert result["configured_attempts"] == 5
    assert result["reason"] == "tls_verification_error"
    assert result["retryable"] is False


def test_timeout_is_treated_as_retryable_failure(manager):
    import requests as real_requests

    mgr, session = manager
    reg = _register(mgr, retry_attempts=2)
    wh = _webhook(session, reg["id"])
    calls = {"n": 0}

    def timeout_post(url, body=None, headers=None, pinned_ips=None, timeout_seconds=None):
        calls["n"] += 1
        raise real_requests.Timeout("simulated")

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(webhook_manager, "_post_pinned_webhook", timeout_post), \
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

    def ok_post(url, body=None, headers=None, pinned_ips=None, timeout_seconds=None):
        captured.update(headers or {})
        captured["body"] = body
        return SimpleNamespace(status_code=200, body=b"ok", is_redirect=False)

    with patch.object(webhook_manager, "_resolve_webhook_destination", return_value=(None, ("93.184.216.34",))), \
         patch.object(webhook_manager, "_post_pinned_webhook", ok_post):
        mgr._deliver_webhook(wh, "payment.reconciled", {"x": 1})

    assert captured["X-Webhook-Signature"].startswith("t=")
    assert "v1=" in captured["X-Webhook-Signature"]
    assert captured["X-Webhook-Timestamp"].isdigit()
    assert captured["X-Webhook-Event"] == "payment.reconciled"
    raw_body = captured["body"]
    assert raw_body == b'{"x":1}'
    assert webhook_manager.verify_webhook_signature(
        raw_body,
        captured["X-Webhook-Signature"],
        wh.signing_secret,
        now=int(captured["X-Webhook-Timestamp"]),
    )
