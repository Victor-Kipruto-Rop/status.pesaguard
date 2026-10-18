"""Alert delivery routing and accounting (hermetic).

Verifies that the Prometheus rule file, the Alertmanager routing file, and the
Prometheus scrape configuration stay aligned, and that AlertingService deliveries
are recorded in the shared alert-delivery telemetry.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import metrics
from alerting_service import AlertingService
from models import AlertDelivery, Base, Discrepancy

BACKEND_ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture(scope="module")
def alerts_yaml() -> dict:
    import yaml

    content = (BACKEND_ROOT / "monitoring" / "alerts.yml").read_text(encoding="utf-8")
    return yaml.safe_load(content)


@pytest.fixture(scope="module")
def alertmanager_yaml() -> dict:
    import yaml

    content = (BACKEND_ROOT / "monitoring" / "alertmanager.yml").read_text(encoding="utf-8")
    return yaml.safe_load(content)


def test_every_prometheus_alert_has_severity_and_service_labels(alerts_yaml):
    for group in alerts_yaml["groups"]:
        for rule in group["rules"]:
            labels = rule.get("labels", {})
            assert labels.get("severity") in {"critical", "warning", "info"}, rule["alert"]
            assert labels.get("service"), rule["alert"]


def test_alertmanager_routes_cover_both_severities_and_receivers_exist(alertmanager_yaml):
    receivers = {receiver["name"] for receiver in alertmanager_yaml["receivers"]}
    assert "pesaguard-default" in receivers

    severity_matchers = set()
    for route in alertmanager_yaml["route"]["routes"]:
        for matcher in route.get("matchers", []):
            key, _, value = matcher.partition("=")
            if key.strip() == "severity":
                severity_matchers.add(value.strip().strip('"'))
        assert route["receiver"] in receivers
    assert {"critical", "warning"}.issubset(severity_matchers)


def test_prometheus_points_at_alertmanager():
    import yaml

    content = (BACKEND_ROOT / "monitoring" / "prometheus.yml").read_text(encoding="utf-8")
    config = yaml.safe_load(content)
    managers = config.get("alerting", {}).get("alertmanagers", [])
    assert any("alertmanager" in str(manager) for manager in managers)


def test_alertmanager_webhook_receivers_target_the_pesa_guard_gateway(alertmanager_yaml):
    urls = []
    for receiver in alertmanager_yaml["receivers"]:
        for config in receiver.get("webhook_configs", []):
            urls.append(config["url"])
            assert config["http_config"]["bearer_token_file"] == (
                "/run/secrets/alertmanager_webhook_secret"
            )
    assert urls, "alertmanager has no webhook receivers"
    assert all(url.endswith("/ops/alerts") for url in urls)


def test_alertmanager_and_receiver_share_a_required_compose_secret():
    import yaml

    compose_path = BACKEND_ROOT / "infra" / "docker" / "docker-compose.yml"
    compose = yaml.safe_load(compose_path.read_text(encoding="utf-8"))
    secret_name = "alertmanager_webhook_secret"
    assert secret_name in compose["services"]["alertmanager"]["secrets"]
    receiver = compose["services"]["webhook_receiver"]
    assert secret_name in receiver["secrets"]
    assert receiver["environment"]["PESAGUARD_ALERTMANAGER_WEBHOOK_SECRET_FILE"] == (
        "/run/secrets/alertmanager_webhook_secret"
    )
    assert secret_name in compose["secrets"]
    assert "PESAGUARD_ALERTMANAGER_WEBHOOK_SECRET_FILE:?" in (
        compose["secrets"][secret_name]["file"]
    )


def test_alerting_service_records_delivery_metrics_per_channel(monkeypatch):
    calls = []

    def fake_send(channel):
        def _send(discrepancy, locale="en", **kwargs):
            calls.append(channel)
            return True

        return _send

    monkeypatch.setattr("alerting_service.send_slack_alert", fake_send("slack"))
    monkeypatch.setattr("alerting_service.send_sms_alert", fake_send("sms"))
    monkeypatch.setattr("alerting_service.is_alert_channel_configured", lambda channel: True)

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    service = AlertingService(session=session, tenant_settings={"alert_channels": ["slack", "sms"]})

    result = service.handle_discrepancy({
        "id": "disc-metrics-1",
        "tenant_id": "tenant-a",
        "trans_id": "TX-METRICS",
        "severity": "critical",
        "status": "missing_payment",
        "anomalies": ["missing_payment"],
        "checked_at": "2026-09-14T00:00:00Z",
    })

    assert {entry["channel"] for entry in result["deliveries"]} == {"slack", "sms"}
    assert calls == ["slack", "sms"]
    snapshot = metrics.telemetry_snapshot()
    assert snapshot["alert_deliveries"].get("slack", 0) >= 1
    assert snapshot["alert_deliveries"].get("sms", 0) >= 1


def test_alerting_service_counts_delivery_failures(monkeypatch):
    def failing_slack(discrepancy, locale="en", **kwargs):
        raise RuntimeError("slack unavailable")

    monkeypatch.setattr("alerting_service.send_slack_alert", failing_slack)

    service = AlertingService(session=None, tenant_settings={"alert_channels": ["slack"]})
    result = service.handle_discrepancy({
        "id": "disc-metrics-2",
        "tenant_id": "tenant-a",
        "trans_id": "TX-FAIL",
        "severity": "critical",
        "status": "missing_payment",
        "anomalies": ["missing_payment"],
        "checked_at": "2026-09-14T00:00:00Z",
    })

    assert result["deliveries"][0]["status"] == "failed"
    snapshot = metrics.telemetry_snapshot()
    assert snapshot["alert_delivery_failures"] >= 1


def test_alert_deduplication_and_delivery_log_are_tenant_scoped(monkeypatch):
    """Same alert id from two tenants must not suppress or collide with the other."""
    calls = []

    def fake_slack(discrepancy, locale="en", **kwargs):
        calls.append(discrepancy.get("tenant_id"))
        return True

    monkeypatch.setattr("alerting_service.send_slack_alert", fake_slack)
    monkeypatch.setattr("alerting_service.is_alert_channel_configured", lambda channel: True)

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    service = AlertingService(session=session, tenant_settings={"alert_channels": ["slack"]})

    payload = {
        "id": "shared-alert",
        "tenant_id": "tenant-a",
        "trans_id": "TX-SHARED",
        "severity": "critical",
        "status": "missing_payment",
        "anomalies": ["missing_payment"],
        "checked_at": "2026-09-14T00:00:00Z",
    }

    first = service.handle_discrepancy(dict(payload))
    other_tenant = service.handle_discrepancy({**payload, "tenant_id": "tenant-b"})
    replay = service.handle_discrepancy(dict(payload))

    assert first["status"] == "queued"
    # Tenant B's alert is delivered even though tenant A used the same alert id.
    assert other_tenant["status"] == "queued"
    assert replay["status"] == "deduped"
    assert calls == ["tenant-a", "tenant-b"]

    rows = session.query(Discrepancy).all()
    assert {row.id for row in rows} == {
        "tenant-a:alert-shared-alert",
        "tenant-b:alert-shared-alert",
    }
    assert {row.tenant_id for row in rows} == {"tenant-a", "tenant-b"}


def test_delivery_audit_log_persists_with_schema_valid_status(monkeypatch):
    """The delivery log row must satisfy ck_discrepancy_status (was 'alerted')."""
    monkeypatch.setattr(
        "alerting_service.send_slack_alert",
        lambda discrepancy, locale="en", **kwargs: True,
    )
    monkeypatch.setattr("alerting_service.is_alert_channel_configured", lambda channel: True)

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine)()
    service = AlertingService(session=session, tenant_settings={"alert_channels": ["slack"]})

    result = service.handle_discrepancy({
        "id": "audit-1",
        "tenant_id": "tenant-a",
        "trans_id": "TX-AUDIT",
        "severity": "critical",
        "status": "missing_payment",
        "anomalies": ["missing_payment"],
        "checked_at": "2026-09-14T00:00:00Z",
    })

    assert result["status"] == "queued"
    row = session.query(Discrepancy).filter_by(tenant_id="tenant-a").one()
    assert row.status in {"needs_review", "reviewed", "resolved", "escalated"}
    assert row.severity in {"info", "warning", "critical"}
    assert row.resolved is False


def test_same_event_replay_retries_failed_delivery_without_resending_success(monkeypatch):
    calls = []
    outcomes = iter([False, True])

    def flaky_slack(discrepancy, locale="en", **kwargs):
        calls.append(discrepancy["event_id"])
        kwargs["on_attempt"](1)
        return next(outcomes)

    monkeypatch.setattr("alerting_service.send_slack_alert", flaky_slack)
    monkeypatch.setattr("alerting_service.is_alert_channel_configured", lambda channel: True)
    monkeypatch.setenv("PESAGUARD_ALERT_MAX_ATTEMPTS", "4")

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine, expire_on_commit=False)()
    service = AlertingService(session=session, tenant_settings={"alert_channels": ["slack"]})
    payload = {
        "id": "retry-alert",
        "event_id": "stable-source-event",
        "tenant_id": "tenant-a",
        "trans_id": "TX-RETRY",
        "severity": "critical",
        "status": "missing_payment",
    }

    first = service.handle_discrepancy(dict(payload))
    retry = service.handle_discrepancy(dict(payload))
    row = session.query(AlertDelivery).filter_by(tenant_id="tenant-a", channel="slack").one()

    assert first["status"] == "failed"
    assert first["deliveries"][0]["status"] == "retrying"
    assert retry["status"] == "queued"
    assert retry["deliveries"][0]["status"] == "sent"
    assert row.status == "sent"
    assert row.attempt_count == 2
    assert calls == ["stable-source-event", "stable-source-event"]

    # A later, distinct event for the same alert is kept in history but does not resend.
    duplicate = service.handle_discrepancy({**payload, "event_id": "another-source-event"})
    assert duplicate["status"] == "deduped"
    assert calls == ["stable-source-event", "stable-source-event"]
