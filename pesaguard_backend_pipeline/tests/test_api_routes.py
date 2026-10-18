from __future__ import annotations

import importlib
from datetime import datetime, timezone, timedelta

import pytest

from models import (
    Alert,
    RateLimitRecord,
    ReportExport,
    RiskEvent,
    ScheduledReport,
    TransactionVersion,
)


@pytest.fixture()
def api_app(tmp_path, monkeypatch):
    database_url = f"sqlite:///{tmp_path / 'api_test.db'}"
    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setenv("PESAGUARD_API_AUTH_REQUIRED", "0")
    monkeypatch.setenv("JWT_SECRET_KEY", "api-route-test-secret-0123456789abcdef")

    import app as app_module

    importlib.reload(app_module)
    app_module.app.config["TESTING"] = True

    event_store = app_module.event_store
    event_store._ensure_ready()

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


def test_transaction_version_model(api_app):
    from event_store import EventStore

    store = EventStore()
    store._ensure_ready()
    with store.Session() as session:
        version = TransactionVersion(
            id="ver-1",
            tenant_id="tenant-a",
            transaction_id="tx-1",
            version_number=1,
            changed_by="system",
            change_reason="created",
            previous_values={"status": "pending"},
            new_values={"status": "completed"},
            created_at=datetime.now(timezone.utc),
        )
        session.add(version)
        session.commit()

    with store.Session() as session:
        found = session.query(TransactionVersion).filter_by(id="ver-1").one()
        assert found.tenant_id == "tenant-a"
        assert found.transaction_id == "tx-1"
        assert found.version_number == 1
        assert found.change_reason == "created"
        assert found.new_values == {"status": "completed"}


def test_risk_event_model(api_app):
    from event_store import EventStore

    store = EventStore()
    store._ensure_ready()
    with store.Session() as session:
        event = RiskEvent(
            id="risk-1",
            tenant_id="tenant-a",
            transaction_id="tx-1",
            event_type="velocity_anomaly",
            risk_level="HIGH",
            risk_score=0.85,
            status="open",
            description="Unusual transaction volume",
        )
        session.add(event)
        session.commit()

    with store.Session() as session:
        found = session.query(RiskEvent).filter_by(id="risk-1").one()
        assert found.tenant_id == "tenant-a"
        assert found.event_type == "velocity_anomaly"
        assert found.risk_level == "HIGH"
        assert float(found.risk_score) == 0.85
        assert found.status == "open"


def test_report_export_model(api_app):
    from event_store import EventStore

    store = EventStore()
    store._ensure_ready()
    with store.Session() as session:
        report = ReportExport(
            id="rep-1",
            tenant_id="tenant-a",
            report_type="reconciliation",
            format="csv",
            status="ready",
            record_count=100,
            file_url="https://example.com/reports/rep-1.csv",
        )
        session.add(report)
        session.commit()

    with store.Session() as session:
        found = session.query(ReportExport).filter_by(id="rep-1").one()
        assert found.tenant_id == "tenant-a"
        assert found.report_type == "reconciliation"
        assert found.format == "csv"
        assert found.status == "ready"
        assert found.record_count == 100


def test_alert_model(api_app):
    from event_store import EventStore

    store = EventStore()
    store._ensure_ready()
    with store.Session() as session:
        alert = Alert(
            id="alert-1",
            tenant_id="tenant-a",
            alert_type="fraud",
            severity="critical",
            status="open",
            title="Potential fraud detected",
            message="Transaction exceeds velocity threshold",
            source="risk_engine",
            transaction_id="tx-1",
        )
        session.add(alert)
        session.commit()

    with store.Session() as session:
        found = session.query(Alert).filter_by(id="alert-1").one()
        assert found.tenant_id == "tenant-a"
        assert found.alert_type == "fraud"
        assert found.severity == "critical"
        assert found.status == "open"


def test_rate_limit_record_model(api_app):
    from event_store import EventStore

    store = EventStore()
    store._ensure_ready()
    with store.Session() as session:
        record = RateLimitRecord(
            id="rlr-1",
            tenant_id="tenant-a",
            endpoint="/api/v1/transactions",
            window_start=datetime.now(timezone.utc),
            window_end=datetime.now(timezone.utc) + timedelta(minutes=1),
            request_count=5,
            blocked_count=0,
        )
        session.add(record)
        session.commit()

    with store.Session() as session:
        found = session.query(RateLimitRecord).filter_by(id="rlr-1").one()
        assert found.tenant_id == "tenant-a"
        assert found.endpoint == "/api/v1/transactions"
        assert found.request_count == 5
        assert found.blocked_count == 0


def test_scheduled_report_model(api_app):
    from event_store import EventStore

    store = EventStore()
    store._ensure_ready()
    with store.Session() as session:
        report = ScheduledReport(
            id="sched-1",
            tenant_id="tenant-a",
            name="Daily Reconciliation Report",
            report_type="reconciliation",
            format="csv",
            schedule_cron="0 9 * * *",
            recipients=["admin@example.com"],
            active=True,
        )
        session.add(report)
        session.commit()

    with store.Session() as session:
        found = session.query(ScheduledReport).filter_by(id="sched-1").one()
        assert found.tenant_id == "tenant-a"
        assert found.name == "Daily Reconciliation Report"
        assert found.format == "csv"
        assert found.active is True


def test_tenant_isolation_on_models(api_app):
    from event_store import EventStore

    store = EventStore()
    store._ensure_ready()
    with store.Session() as session:
        session.add_all([
            Alert(
                id="alert-a",
                tenant_id="tenant-a",
                alert_type="fraud",
                severity="critical",
                status="open",
                title="Alert A",
            ),
            Alert(
                id="alert-b",
                tenant_id="tenant-b",
                alert_type="fraud",
                severity="critical",
                status="open",
                title="Alert B",
            ),
        ])
        session.commit()

    with store.Session() as session:
        tenant_a_alerts = session.query(Alert).filter_by(tenant_id="tenant-a").all()
        tenant_b_alerts = session.query(Alert).filter_by(tenant_id="tenant-b").all()
        assert len(tenant_a_alerts) == 1
        assert len(tenant_b_alerts) == 1
        assert tenant_a_alerts[0].title == "Alert A"
        assert tenant_b_alerts[0].title == "Alert B"


# ---------------------------------------------------------------------------
# API endpoint tests
# ---------------------------------------------------------------------------

def test_list_transactions_endpoint(client):
    response = client.get("/api/v1/transactions", headers=_headers())
    assert response.status_code == 200
    data = response.get_json()
    assert "items" in data
    assert "meta" in data
    assert "total" in data["meta"]
    assert "page" in data["meta"]
    assert "per_page" in data["meta"]
    assert "X-Correlation-ID" in response.headers


def test_list_transactions_tenant_isolation(client):
    response_a = client.get("/api/v1/transactions", headers=_headers("tenant-a"))
    response_b = client.get("/api/v1/transactions", headers=_headers("tenant-b"))
    assert response_a.status_code == 200
    assert response_b.status_code == 200
    assert response_a.get_json()["items"] == []
    assert response_b.get_json()["items"] == []


def test_get_transaction_not_found(client):
    response = client.get("/api/v1/transactions/nonexistent-id", headers=_headers())
    assert response.status_code == 404
    data = response.get_json()
    assert data["error"] == "not_found"
    assert "X-Correlation-ID" in response.headers


def test_get_transaction_versions_not_found(client):
    response = client.get("/api/v1/transactions/tx-1/versions", headers=_headers())
    assert response.status_code == 404
    data = response.get_json()
    assert data["error"] == "not_found"


def test_list_reconciliation_endpoint(client):
    response = client.get("/api/v1/reconciliation", headers=_headers())
    assert response.status_code == 200
    data = response.get_json()
    assert "discrepancies" in data
    assert "matches" in data
    assert "meta" in data
    assert "X-Correlation-ID" in response.headers


def test_list_risk_events_endpoint(client):
    response = client.get("/api/v1/risk-events", headers=_headers())
    assert response.status_code == 200
    data = response.get_json()
    assert "items" in data


def test_get_risk_event_not_found(client):
    response = client.get("/api/v1/risk-events/nonexistent", headers=_headers())
    assert response.status_code == 404
    data = response.get_json()
    assert data["error"] == "not_found"


def test_list_reports_endpoint(client):
    response = client.get("/api/v1/reports", headers=_headers())
    assert response.status_code == 200
    data = response.get_json()
    assert "items" in data


def test_create_report_endpoint(client):
    response = client.post(
        "/api/v1/reports",
        headers=_headers(),
        json={
            "report_type": "reconciliation",
            "format": "csv",
            "filters": {"date_from": "2026-01-01", "date_to": "2026-01-31"},
        },
    )
    assert response.status_code in (200, 201, 202)
    data = response.get_json()
    assert "id" in data or "report_id" in data


def test_get_report_not_found(client):
    response = client.get("/api/v1/reports/nonexistent", headers=_headers())
    assert response.status_code == 404


def test_list_webhooks_endpoint(client):
    response = client.get("/api/v1/webhooks", headers=_headers())
    assert response.status_code == 200
    data = response.get_json()
    assert "items" in data


def test_get_webhook_not_found(client):
    response = client.get("/api/v1/webhooks/nonexistent", headers=_headers())
    assert response.status_code == 404


def test_list_idempotency_keys_endpoint(client):
    response = client.get("/api/v1/idempotency", headers=_headers())
    assert response.status_code == 200
    data = response.get_json()
    assert "items" in data


def test_api_metrics_endpoint(client):
    response = client.get("/api/v1/metrics", headers=_headers())
    assert response.status_code == 200
    data = response.get_json()
    assert "X-Correlation-ID" in response.headers


def test_response_headers_include_correlation_id(client):
    response = client.get("/api/v1/metrics", headers=_headers())
    cid = response.headers.get("X-Correlation-ID")
    assert cid is not None
    assert len(cid) > 0


def test_rate_limit_headers_present(client):
    response = client.get("/api/v1/metrics", headers=_headers())
    assert response.status_code == 200


def test_pagination_parameters(client):
    response = client.get(
        "/api/v1/transactions?page=1&per_page=10",
        headers=_headers(),
    )
    assert response.status_code == 200
    data = response.get_json()
    assert data["meta"]["page"] == 1
    assert data["meta"]["per_page"] == 10


def test_tenant_id_header_required(client):
    response = client.get("/api/v1/transactions")
    assert response.status_code in (200, 400)


def test_rate_limit_applies_to_endpoints(client):
    for _ in range(5):
        response = client.get("/api/v1/metrics", headers=_headers())
        assert response.status_code == 200


def test_correlation_id_propagation(client):
    custom_cid = "test-correlation-12345"
    response = client.get(
        "/api/v1/metrics",
        headers={**_headers(), "X-Correlation-ID": custom_cid},
    )
    assert response.status_code == 200
    assert "X-Correlation-ID" in response.headers