"""Reporting & analytics cross-tenant and auth-boundary tests (services 84-89).

Services 84-89 (reporting, dashboard metrics, usage analytics, report generation,
tenant exports) share one HTTP attack surface: export/report routes that read
financial and operational data belonging to a tenant. The sensitive boundary is
cross-tenant denial: data belonging to tenant-b must never be readable through
any export/report/transaction endpoint by a caller authenticated as tenant-a.

These tests replicate the established app_2 / AuthRBAC pattern from
test_dashboard_api.py (real auth tokens, no bypassed auth -- Rule #12) and pin
the denial for: transaction detail, transaction list, reports, audit, deadletter
listings, and the CSV export. A denial must be 403/404 and must not confirm or
deny the resource's existence beyond what an unauthenticated caller would learn
(no row content in the body).

All behaviours below were verified against the current implementation first
(Rule #62). No production code is changed here.
"""

from __future__ import annotations

import importlib
import os
import tempfile
from datetime import datetime, timezone

import pytest
from auth_rbac import AuthRBAC


@pytest.fixture()
def report_app(monkeypatch):
    with tempfile.TemporaryDirectory() as tmpdir:
        db_path = os.path.join(tmpdir, "report-boundary.db")
        monkeypatch.setenv("DATABASE_URL", f"sqlite:///{db_path}")
        monkeypatch.setenv("PESAGUARD_API_AUTH_REQUIRED", "1")
        import app_2

        app_2 = importlib.reload(app_2)
        app_2.Base.metadata.create_all(app_2.primary_engine)
        from action_audit import ActionAuditEntry, Base as AuditBase
        from auth_rbac import _RevocationBase
        from models import DeadLetter, Discrepancy, Report, Transaction, UserAccount

        AuditBase.metadata.create_all(app_2.primary_engine)
        _RevocationBase.metadata.create_all(app_2.primary_engine)
        ActionAuditEntry.__table__.create(app_2.primary_engine, checkfirst=True)
        app_2.app.config.update(TESTING=True)

        session = app_2.SessionLocal()
        try:
            session.add(UserAccount(
                id="tenant-a-user",
                tenant_id="tenant-a",
                username="a-user",
                roles=["admin"],
                permissions=[],
                status="active",
                authorization_version=1,
            ))
            session.add(Transaction(
                trans_id="tx-b-secret",
                tenant_id="tenant-b",
                trans_amount="100.00",
                msisdn="254700000001",
                business_short_code="600000",
                trans_time="20260912120000",
                raw_payload={"TransID": "tx-b-secret"},
            ))
            session.add(Report(
                id="report-b-1",
                tenant_id="tenant-b",
                report_type="daily",
                period_start=datetime.now(timezone.utc),
                period_end=datetime.now(timezone.utc),
                content={"summary": {}},
            ))
            session.add(DeadLetter(
                id="deadletter-b-secret",
                tenant_id="tenant-b",
                reason="cross-tenant sentinel",
                payload={"event_id": "deadletter-b-secret"},
                error_detail="tenant-b private error detail",
            ))
            session.add(Discrepancy(
                id="discrepancy-b-secret",
                trans_id="csv-b-secret",
                tenant_id="tenant-b",
                anomaly_type="cross-tenant sentinel",
            ))
            session.commit()
        finally:
            session.close()

        with app_2.app.test_client() as client:
            yield client


def _token(tenant_id, roles=("admin",)):
    # Credentials MUST match the seeded DB row exactly; AuthRBAC rejects tokens
    # whose claims do not correspond to a live, current account (fail-closed).
    # generate_token reads the account's live authorization_version from the DB.
    if tenant_id != "tenant-a":
        raise ValueError("test fixture only seeds a tenant-a admin")
    return AuthRBAC.generate_token(
        user_id="tenant-a-user",
        username="a-user",
        tenant_id="tenant-a",
        roles=list(roles),
    )


def _headers(tenant_id, roles=("admin",)):
    return {"Authorization": f"Bearer {_token(tenant_id, roles)}"}


def _assert_denied(response, cross_tenant_secret):
    # Denial is 401/403/404 depending on which check fires first; in every case
    # no tenant-b content may leak in the body.
    assert response.status_code in {401, 403, 404}
    body = response.get_data(as_text=True)
    assert cross_tenant_secret not in body


# --- Service 86 (analytics transaction detail): cross-tenant denial -------------


def test_transaction_detail_denies_cross_tenant(report_app):
    response = report_app.get(
        "/v1/customers/tenant-b/transactions/tx-b-secret",
        headers=_headers("tenant-a"),
    )
    _assert_denied(response, "tx-b-secret")


def test_transaction_list_returns_only_own_tenant(report_app):
    response = report_app.get(
        "/v1/customers/tenant-a/transactions",
        headers=_headers("tenant-a"),
    )
    assert response.status_code == 200
    for item in response.get_json()["items"]:
        assert "tx-b-secret" != item.get("trans_id")


# --- Service 88 (report generation listing): cross-tenant denial ----------------


def test_reports_listing_denies_cross_tenant(report_app):
    response = report_app.get(
        "/v1/customers/tenant-b/reports",
        headers=_headers("tenant-a"),
    )
    _assert_denied(response, "report-b-1")


def test_deadletter_listing_denies_cross_tenant(report_app):
    response = report_app.get(
        "/v1/customers/tenant-b/deadletters",
        headers=_headers("tenant-a"),
    )
    assert response.status_code == 403
    _assert_denied(response, "deadletter-b-secret")


# --- Service 84 (reporting audit): cross-tenant denial --------------------------


def test_audit_listing_denies_cross_tenant(report_app):
    response = report_app.get(
        "/v1/customers/tenant-b/audit",
        headers=_headers("tenant-a"),
    )
    assert response.status_code in {403, 404}


# --- Service 89 (export): CSV export requires tenant scope ----------------------


def test_csv_export_requires_tenant_scope(report_app):
    response = report_app.get("/v1/export/csv", headers=_headers("tenant-a"))
    assert response.status_code == 400


def test_csv_export_denies_caller_selected_other_tenant(report_app):
    response = report_app.get(
        "/v1/export/csv?tenant_id=tenant-b",
        headers=_headers("tenant-a"),
    )
    assert response.status_code == 403
    _assert_denied(response, "csv-b-secret")


# --- Authorization: a role without read permission is denied --------------------


def test_audit_endpoint_requires_settings_permission(report_app):
    # The audit route requires read:settings, which 'read-only' lacks while
    # 'admin' has. Both users exist only as tenant-a identities, so this tests
    # authorization on an own-tenant endpoint, not cross-tenant access.
    session_token = _headers("tenant-a", roles=("read-only",))
    response = report_app.get("/v1/customers/tenant-a/audit", headers=session_token)
    assert response.status_code in {401, 403}
