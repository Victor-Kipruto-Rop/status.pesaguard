"""Tests for the alert API endpoints (GET/POST /api/v1/alerts*).

Covers list, get, acknowledge, resolve, suppress, escalation lookup, and
escalate actions, including tenant scoping, validation, and conflict handling.
"""
from __future__ import annotations

import importlib
import sys
from datetime import datetime, timezone, timedelta
from unittest.mock import MagicMock

import pytest
from tenant_context import tenant_scope

from models import Alert, AlertDelivery, EscalationRule, OnCallRotation, Base


@pytest.fixture()
def api_app(tmp_path, monkeypatch):
    """Flask app with alert routes wired to a per-test SQLite database."""
    database_url = f"sqlite:///{tmp_path / 'alert_api_test.db'}"
    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setenv("PESAGUARD_API_AUTH_REQUIRED", "0")
    monkeypatch.setenv("JWT_SECRET_KEY", "alert-api-test-secret-0123456789abcdef!!")

    # Remove cached alert_api_routes so it is (re)imported with this test's
    # DATABASE_URL, giving each test its own EventStore/database.
    sys.modules.pop("alert_api_routes", None)

    import app as app_module

    importlib.reload(app_module)
    app_module.app.config["TESTING"] = True
    app_module.app.event_store = app_module.event_store

    event_store = app_module.event_store
    event_store._ensure_ready()
    Base.metadata.create_all(event_store.engine)

    # Attach for test access to the same EventStore the alert routes use.
    app_module.app.event_store = event_store

    yield app_module.app

    event_store.engine.dispose()


@pytest.fixture()
def client(api_app):
    return api_app.test_client()


def _headers(tenant_id: str = "tenant-a", extra: dict | None = None) -> dict:
    h = {"X-Tenant-ID": tenant_id}
    if extra:
        h.update(extra)
    return h


def _fake_user():
    user = MagicMock()
    user.username = "test-operator"
    user.id = "operator-1"
    user.tenant_id = "tenant-a"
    return user


def _now():
    return datetime.now(timezone.utc)


def _seed_alert(session, *, tenant_id="tenant-a", alert_id="alert-1", status="open",
                severity="warning", alert_type="fraud_detection", fingerprint=None):
    if fingerprint is None:
        fingerprint = f"fp-{alert_id}"
    alert = Alert(
        id=alert_id,
        tenant_id=tenant_id,
        alert_type=alert_type,
        severity=severity,
        status=status,
        title=f"Test {alert_type}",
        message="Test alert message",
        source="system",
        transaction_id="tx-1",
        fingerprint=fingerprint,
        created_at=_now(),
        updated_at=_now(),
    )
    session.add(alert)
    session.commit()
    return alert


def _seed_escalation_rule(session, *, tenant_id="tenant-a", rule_id="rule-1",
                          severity="warning", active=True, priority=10):
    rule = EscalationRule(
        id=rule_id,
        tenant_id=tenant_id,
        name=f"Escalate on {severity}",
        condition_field="severity",
        condition_operator="equals",
        condition_value=severity,
        action="escalate",
        active=active,
        priority=priority,
    )
    session.add(rule)
    session.commit()
    return rule


def _seed_on_call(session, *, tenant_id="tenant-a", rotation_id="rotation-1"):
    rotation = OnCallRotation(
        id=rotation_id,
        tenant_id=tenant_id,
        operator_id="op-1",
        operator_name="Test Operator",
        operator_email="ops@example.com",
        shift_start=_now(),
        shift_end=_now() + timedelta(days=1),
        is_active=True,
        escalation_level=1,
    )
    session.add(rotation)
    session.commit()
    return rotation


def _with_fake_user(api_app, monkeypatch):
    """Patch get_current_user so _require_actor() succeeds on mutation routes."""
    import alert_api_routes

    monkeypatch.setattr(alert_api_routes, "get_current_user", _fake_user)


# ---------------------------------------------------------------------------
# List alerts
# ---------------------------------------------------------------------------


def test_list_alerts_returns_empty_when_no_alerts(client, api_app, monkeypatch):
    with tenant_scope("tenant-a"):
        response = client.get("/api/v1/alerts", headers=_headers("tenant-a"))
    assert response.status_code == 200
    data = response.get_json()
    assert data["items"] == []
    assert data["total"] == 0
    assert data["page"] == 1
    assert data["per_page"] == 20


def test_list_alerts_returns_seeded_alerts(client, api_app, monkeypatch):
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-a", severity="critical", status="open")
        _seed_alert(session, alert_id="alert-b", severity="info", status="resolved")

    with tenant_scope("tenant-a"):
        response = client.get("/api/v1/alerts", headers=_headers("tenant-a"))
    assert response.status_code == 200
    data = response.get_json()
    assert data["total"] == 2
    ids = {item["id"] for item in data["items"]}
    assert ids == {"alert-a", "alert-b"}


def test_list_alerts_filters_by_status(client, api_app, monkeypatch):
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="open-1", status="open")
        _seed_alert(session, alert_id="res-1", status="resolved")

    with tenant_scope("tenant-a"):
        response = client.get(
            "/api/v1/alerts?status=open", headers=_headers("tenant-a")
        )
    assert response.status_code == 200
    data = response.get_json()
    assert data["total"] == 1
    assert data["items"][0]["status"] == "open"


def test_list_alerts_filters_by_severity(client, api_app, monkeypatch):
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="crit-1", severity="critical")
        _seed_alert(session, alert_id="info-1", severity="info")

    with tenant_scope("tenant-a"):
        response = client.get(
            "/api/v1/alerts?severity=critical", headers=_headers("tenant-a")
        )
    assert response.status_code == 200
    data = response.get_json()
    assert data["total"] == 1
    assert data["items"][0]["severity"] == "critical"


def test_list_alerts_invalid_status_rejected(client, api_app, monkeypatch):
    with tenant_scope("tenant-a"):
        response = client.get(
            "/api/v1/alerts?status=bogus", headers=_headers("tenant-a")
        )
    assert response.status_code == 400
    assert response.get_json()["error"] == "invalid_status"


def test_list_alerts_invalid_severity_rejected(client, api_app, monkeypatch):
    with tenant_scope("tenant-a"):
        response = client.get(
            "/api/v1/alerts?severity=bogus", headers=_headers("tenant-a")
        )
    assert response.status_code == 400
    assert response.get_json()["error"] == "invalid_severity"


def test_list_alerts_pagination(client, api_app, monkeypatch):
    event_store = api_app.event_store
    with event_store.Session() as session:
        for i in range(5):
            _seed_alert(session, alert_id=f"alert-{i}")

    with tenant_scope("tenant-a"):
        response = client.get(
            "/api/v1/alerts?page=1&per_page=2", headers=_headers("tenant-a")
        )
    assert response.status_code == 200
    data = response.get_json()
    assert data["page"] == 1
    assert data["per_page"] == 2
    assert len(data["items"]) == 2
    assert data["total"] == 5


# ---------------------------------------------------------------------------
# Get single alert
# ---------------------------------------------------------------------------


def test_get_alert_returns_alert(client, api_app, monkeypatch):
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", severity="warning")

    with tenant_scope("tenant-a"):
        response = client.get("/api/v1/alerts/alert-1", headers=_headers("tenant-a"))
    assert response.status_code == 200
    data = response.get_json()
    assert data["id"] == "alert-1"
    assert data["severity"] == "warning"
    assert data["status"] == "open"
    assert "created_at" in data
    assert "updated_at" in data


def test_get_alert_not_found(client, api_app, monkeypatch):
    with tenant_scope("tenant-a"):
        response = client.get(
            "/api/v1/alerts/does-not-exist", headers=_headers("tenant-a")
        )
    assert response.status_code == 404
    assert response.get_json()["error"] == "not_found"


# ---------------------------------------------------------------------------
# Acknowledge
# ---------------------------------------------------------------------------


def test_acknowledge_alert_transitions_to_acknowledged(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", status="open")

    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/alert-1/acknowledge",
            headers=_headers("tenant-a"),
            json={"note": "Reviewed by operator"},
        )
    assert response.status_code == 200
    data = response.get_json()
    assert data["id"] == "alert-1"
    assert data["status"] == "acknowledged"
    assert data["acknowledged_by"] == "test-operator"
    assert data["acknowledged_at"] is not None


def test_acknowledge_alert_not_found(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/missing/acknowledge",
            headers=_headers("tenant-a"),
            json={},
        )
    assert response.status_code == 404
    assert response.get_json()["error"] == "not_found"


def test_acknowledge_alert_already_resolved(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", status="resolved")

    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/alert-1/acknowledge",
            headers=_headers("tenant-a"),
            json={},
        )
    assert response.status_code == 409
    assert response.get_json()["error"] == "conflict"


def test_acknowledge_alert_already_acknowledged(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", status="acknowledged")

    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/alert-1/acknowledge",
            headers=_headers("tenant-a"),
            json={},
        )
    assert response.status_code == 409
    assert response.get_json()["error"] == "conflict"


# ---------------------------------------------------------------------------
# Resolve
# ---------------------------------------------------------------------------


def test_resolve_alert_requires_outcome(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", status="open")

    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/alert-1/resolve",
            headers=_headers("tenant-a"),
            json={},
        )
    assert response.status_code == 400
    assert response.get_json()["error"] == "invalid_outcome"


def test_resolve_alert_transitions_to_resolved(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", status="open")

    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/alert-1/resolve",
            headers=_headers("tenant-a"),
            json={"outcome": "false_positive", "note": "Duplicate transaction"},
        )
    assert response.status_code == 200
    data = response.get_json()
    assert data["id"] == "alert-1"
    assert data["status"] == "resolved"
    assert data["resolved_at"] is not None
    assert data["resolution_note"] == "Duplicate transaction"
    assert data["resolution_outcome"] == "false_positive"


def test_resolve_alert_already_resolved(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", status="resolved")

    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/alert-1/resolve",
            headers=_headers("tenant-a"),
            json={"outcome": "confirmed_fraud"},
        )
    assert response.status_code == 409
    assert response.get_json()["error"] == "conflict"


# ---------------------------------------------------------------------------
# Suppress
# ---------------------------------------------------------------------------


def test_suppress_alert_transitions_to_suppressed(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", status="open")

    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/alert-1/suppress",
            headers=_headers("tenant-a"),
            json={"note": "False positive, suppressed"},
        )
    assert response.status_code == 200
    data = response.get_json()
    assert data["id"] == "alert-1"
    assert data["status"] == "suppressed"


def test_suppress_alert_already_suppressed(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", status="suppressed")

    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/alert-1/suppress",
            headers=_headers("tenant-a"),
            json={},
        )
    assert response.status_code == 409
    assert response.get_json()["error"] == "conflict"


def test_create_alert_suppresses_repeated_notifications_and_keeps_duplicate_history(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    monkeypatch.delenv("ALERT_EMAIL_RECIPIENTS", raising=False)
    payload = {
        "id": "recon-exception-100",
        "event_id": "source-event-1",
        "category": "amount_mismatch",
        "severity": "critical",
        "title": "Amount mismatch detected",
        "message": "The provider amount differs from the ledger amount.",
        "transaction_id": "TX-100",
    }

    with tenant_scope("tenant-a"):
        first = client.post("/api/v1/alerts", headers=_headers("tenant-a"), json=payload)
        duplicate = client.post(
            "/api/v1/alerts",
            headers=_headers("tenant-a"),
            json={**payload, "event_id": "source-event-2"},
        )
        replay = client.post("/api/v1/alerts", headers=_headers("tenant-a"), json=payload)

    assert first.status_code == 201
    assert duplicate.status_code == 200
    assert replay.status_code == 200
    assert first.get_json()["id"] == duplicate.get_json()["id"]
    assert duplicate.get_json()["duplicate_suppressed"] is True
    assert duplicate.get_json()["duplicate_count"] == 1
    assert replay.get_json()["event_replay"] is True
    assert replay.get_json()["duplicate_count"] == 1

    with tenant_scope("tenant-a"):
        history = client.get(
            f"/api/v1/alerts/{first.get_json()['id']}/events",
            headers=_headers("tenant-a"),
        )
    assert history.status_code == 200
    assert [event["event_type"] for event in history.get_json()["items"]] == [
        "alert.created",
        "alert.duplicate_suppressed",
    ]

    with tenant_scope("tenant-a"):
        first_page = client.get(
            f"/api/v1/alerts/{first.get_json()['id']}/events?page=1&per_page=1",
            headers=_headers("tenant-a"),
        )
        second_page = client.get(
            f"/api/v1/alerts/{first.get_json()['id']}/events?page=2&per_page=1",
            headers=_headers("tenant-a"),
        )
    assert first_page.get_json()["total"] == 2
    assert first_page.get_json()["page"] == 1
    assert second_page.get_json()["items"][0]["event_type"] == "alert.duplicate_suppressed"


def test_dead_lettered_direct_delivery_can_be_retried_with_same_source_event(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    monkeypatch.delenv("ALERT_EMAIL_RECIPIENTS", raising=False)
    enqueued = []
    monkeypatch.setattr(
        "background_tasks.enqueue_notification_event",
        lambda event: enqueued.append(event) or {"status": "queued", "job_id": "retry-job"},
    )
    payload = {
        "id": "retry-source-discrepancy",
        "event_id": "retry-source-event",
        "category": "amount_mismatch",
        "severity": "critical",
        "title": "Amount mismatch",
        "transaction_id": "TX-RETRY",
    }
    with tenant_scope("tenant-a"):
        created = client.post("/api/v1/alerts", headers=_headers("tenant-a"), json=payload)
    alert_id = created.get_json()["id"]
    with api_app.event_store.Session() as session:
        delivery = AlertDelivery(
            id="delivery-dead-letter",
            tenant_id="tenant-a",
            alert_id=alert_id,
            channel="slack",
            idempotency_key=f"{alert_id}:slack",
            status="dead_letter",
            attempt_count=6,
            retry_generation=0,
        )
        session.add(delivery)
        session.commit()

    with tenant_scope("tenant-a"):
        response = client.post(
            f"/api/v1/alerts/{alert_id}/deliveries/delivery-dead-letter/retry",
            headers=_headers("tenant-a"),
        )

    assert response.status_code == 202
    assert response.get_json()["status"] == "queued"
    assert response.get_json()["retry_generation"] == 1
    assert enqueued[0]["event_id"] == "retry-source-event"
    assert enqueued[0]["context"]["deduplication_key"] == "retry-source-discrepancy"
    with api_app.event_store.Session() as session:
        persisted = session.query(AlertDelivery).filter_by(id="delivery-dead-letter").one()
        assert persisted.status == "queued"
        assert persisted.attempt_count == 6
        assert persisted.retry_generation == 1


def test_assignment_and_investigation_are_durable_and_acknowledge_open_alert(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    with api_app.event_store.Session() as session:
        _seed_alert(session, alert_id="alert-workflow", status="open")

    with tenant_scope("tenant-a"):
        assignment = client.post(
            "/api/v1/alerts/alert-workflow/assign",
            headers=_headers("tenant-a"),
            json={"assigned_to": "operator-2"},
        )
        investigation = client.post(
            "/api/v1/alerts/alert-workflow/investigate",
            headers=_headers("tenant-a"),
            json={"note": "Compared provider statement and ledger entry."},
        )

    assert assignment.status_code == 200
    assert assignment.get_json()["assigned_to"] == "operator-2"
    assert investigation.status_code == 200
    assert investigation.get_json()["status"] == "acknowledged"
    assert investigation.get_json()["acknowledged_by"] == "test-operator"
    assert investigation.get_json()["investigation_notes"] == "Compared provider statement and ledger entry."

    with api_app.event_store.Session() as session:
        persisted = session.query(Alert).filter_by(id="alert-workflow", tenant_id="tenant-a").one()
        assert persisted.assigned_to == "operator-2"
        assert persisted.status == "acknowledged"


def test_resolve_alert_allows_transition_from_acknowledged(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    with api_app.event_store.Session() as session:
        _seed_alert(session, alert_id="alert-acknowledged", status="acknowledged")

    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/alert-acknowledged/resolve",
            headers=_headers("tenant-a"),
            json={"outcome": "inconclusive", "note": "Awaiting additional provider evidence."},
        )

    assert response.status_code == 200
    assert response.get_json()["status"] == "resolved"
    assert response.get_json()["resolution_outcome"] == "inconclusive"


# ---------------------------------------------------------------------------
# Escalation lookup
# ---------------------------------------------------------------------------


def test_get_alert_escalation_returns_rules(client, api_app, monkeypatch):
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", severity="critical")
        _seed_escalation_rule(session, severity="critical")
        _seed_on_call(session)

    with tenant_scope("tenant-a"):
        response = client.get(
            "/api/v1/alerts/alert-1/escalation", headers=_headers("tenant-a")
        )
    assert response.status_code == 200
    data = response.get_json()
    assert data["alert_id"] == "alert-1"
    assert data["current_severity"] == "critical"
    assert len(data["escalation_rules"]) == 1
    assert data["escalation_rules"][0]["condition_value"] == "critical"
    assert data["action"] == "rule:rule-1:escalate"
    assert data["on_call"] is not None
    assert data["on_call"]["operator_name"] == "Test Operator"


def test_get_alert_escalation_not_found(client, api_app, monkeypatch):
    with tenant_scope("tenant-a"):
        response = client.get(
            "/api/v1/alerts/missing/escalation", headers=_headers("tenant-a")
        )
    assert response.status_code == 404
    assert response.get_json()["error"] == "not_found"


# ---------------------------------------------------------------------------
# Escalate
# ---------------------------------------------------------------------------


def test_escalate_alert_raises_severity(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", severity="warning", status="open")

    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/alert-1/escalate",
            headers=_headers("tenant-a"),
            json={},
        )
    assert response.status_code == 200
    data = response.get_json()
    assert data["id"] == "alert-1"
    assert data["severity"] == "critical"
    assert data["status"] == "open"


def test_escalate_alert_not_found(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/missing/escalate",
            headers=_headers("tenant-a"),
            json={},
        )
    assert response.status_code == 404
    assert response.get_json()["error"] == "not_found"


def test_escalate_alert_already_resolved(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", severity="warning", status="resolved")

    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts/alert-1/escalate",
            headers=_headers("tenant-a"),
            json={},
        )
    assert response.status_code == 409
    assert response.get_json()["error"] == "conflict"


# ---------------------------------------------------------------------------
# Create alert
# ---------------------------------------------------------------------------


def _create_payload(**overrides):
    payload = {
        "severity": "warning",
        "alert_type": "fraud_detection",
        "title": "Suspected fraud pattern",
        "message": "Velocity anomaly on merchant payouts",
        "source": "reconciliation",
        "transaction_id": "tx-create-1",
    }
    payload.update(overrides)
    return payload


def test_create_alert_persists_and_returns_201(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts",
            headers={**_headers("tenant-a"), "Idempotency-Key": "create-evt-1"},
            json=_create_payload(),
        )
    assert response.status_code == 201
    data = response.get_json()
    assert data["tenant_id"] == "tenant-a"
    assert data["severity"] == "warning"
    assert data["status"] == "open"
    assert data["alert_type"] == "fraud_detection"
    assert data["title"] == "Suspected fraud pattern"
    assert data["duplicate_suppressed"] is False
    assert data["event_replay"] is False


def test_create_alert_same_event_id_returns_duplicate_suppressed(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    with tenant_scope("tenant-a"):
        first = client.post(
            "/api/v1/alerts",
            headers={**_headers("tenant-a"), "Idempotency-Key": "dedupe-evt-9"},
            json=_create_payload(),
        )
    assert first.status_code == 201

    with tenant_scope("tenant-a"):
        second = client.post(
            "/api/v1/alerts",
            headers={**_headers("tenant-a"), "Idempotency-Key": "dedupe-evt-9"},
            json=_create_payload(),
        )
    assert second.status_code == 200
    data = second.get_json()
    assert data["id"] == first.get_json()["id"]
    assert data["duplicate_suppressed"] is True


def test_create_alert_rejects_invalid_severity(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts",
            headers=_headers("tenant-a"),
            json=_create_payload(severity="catastrophic"),
        )
    assert response.status_code == 400
    assert response.get_json()["error"] == "invalid_severity"


def test_create_alert_rejects_missing_category(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    payload = _create_payload()
    del payload["alert_type"]
    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts", headers=_headers("tenant-a"), json=payload
        )
    assert response.status_code == 400
    assert response.get_json()["error"] == "invalid_category"


def test_create_alert_rejects_missing_title(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    payload = _create_payload()
    del payload["title"]
    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts", headers=_headers("tenant-a"), json=payload
        )
    assert response.status_code == 400
    assert response.get_json()["error"] == "invalid_title"


def test_create_alert_rejects_non_object_body(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    with tenant_scope("tenant-a"):
        response = client.post(
            "/api/v1/alerts",
            headers=_headers("tenant-a"),
            data="not-json",
            content_type="application/json",
        )
    assert response.status_code == 400
    assert response.get_json()["error"] == "invalid_request"

def test_alert_events_records_acknowledge_transition(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, alert_id="alert-1", status="open")

    with tenant_scope("tenant-a"):
        ack = client.post(
            "/api/v1/alerts/alert-1/acknowledge",
            headers=_headers("tenant-a"),
            json={"note": "On it"},
        )
    assert ack.status_code == 200

    with tenant_scope("tenant-a"):
        response = client.get(
            "/api/v1/alerts/alert-1/events", headers=_headers("tenant-a")
        )
    assert response.status_code == 200
    data = response.get_json()
    assert data["alert_id"] == "alert-1"
    assert len(data["items"]) == 1
    entry = data["items"][0]
    assert entry["event_type"] == "alert.acknowledged"
    assert entry["actor"] == "test-operator"
    assert entry["from_status"] == "open"
    assert entry["to_status"] == "acknowledged"


def test_alert_events_not_found_for_unknown_alert(client, api_app, monkeypatch):
    with tenant_scope("tenant-a"):
        response = client.get(
            "/api/v1/alerts/missing/events", headers=_headers("tenant-a")
        )
    assert response.status_code == 404
    assert response.get_json()["error"] == "not_found"


def test_alert_events_are_tenant_isolated(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, tenant_id="tenant-a", alert_id="alert-a")

    with tenant_scope("tenant-b"):
        response = client.get(
            "/api/v1/alerts/alert-a/events", headers=_headers("tenant-b")
        )
    assert response.status_code == 404


def test_create_alert_persists_lifecycle_event(client, api_app, monkeypatch):
    _with_fake_user(api_app, monkeypatch)
    with tenant_scope("tenant-a"):
        created = client.post(
            "/api/v1/alerts",
            headers={**_headers("tenant-a"), "Idempotency-Key": "lifecycle-evt-2"},
            json=_create_payload(),
        )
    assert created.status_code == 201
    alert_id = created.get_json()["id"]

    with tenant_scope("tenant-a"):
        events = client.get(
            f"/api/v1/alerts/{alert_id}/events", headers=_headers("tenant-a")
        )
    assert events.status_code == 200
    items = events.get_json()["items"]
    assert len(items) == 1
    assert items[0]["event_type"] == "alert.created"


# ---------------------------------------------------------------------------
# Tenant isolation
# ---------------------------------------------------------------------------


def test_alerts_are_tenant_isolated(client, api_app, monkeypatch):
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, tenant_id="tenant-a", alert_id="alert-a")
        _seed_alert(session, tenant_id="tenant-b", alert_id="alert-b")

    with tenant_scope("tenant-a"):
        response = client.get("/api/v1/alerts", headers=_headers("tenant-a"))
    assert response.status_code == 200
    data = response.get_json()
    assert data["total"] == 1
    assert data["items"][0]["tenant_id"] == "tenant-a"

    with tenant_scope("tenant-b"):
        response = client.get("/api/v1/alerts", headers=_headers("tenant-b"))
    assert response.status_code == 200
    data = response.get_json()
    assert data["total"] == 1
    assert data["items"][0]["tenant_id"] == "tenant-b"


def test_get_alert_cross_tenant_returns_not_found(client, api_app, monkeypatch):
    event_store = api_app.event_store
    with event_store.Session() as session:
        _seed_alert(session, tenant_id="tenant-a", alert_id="alert-a")

    # Tenant-b tries to read tenant-a's alert
    with tenant_scope("tenant-b"):
        response = client.get(
            "/api/v1/alerts/alert-a", headers=_headers("tenant-b")
        )
    assert response.status_code == 404


# ---------------------------------------------------------------------------
# Tenant context required
# ---------------------------------------------------------------------------


def test_routes_require_tenant_context(client, api_app, monkeypatch):
    # No X-Tenant-ID header and no tenant_scope set -> current_tenant_id() is None
    response = client.get("/api/v1/alerts")
    assert response.status_code == 400
    assert response.get_json()["error"] == "tenant_context_required"
