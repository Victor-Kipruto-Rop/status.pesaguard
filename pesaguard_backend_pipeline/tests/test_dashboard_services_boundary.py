"""Dashboard services cross-tenant, authorization, and anonymous-surface tests (100-108).

Services 100-108 are the operational dashboard surfaces served by the canonical
``app_2`` (``api/dashboard_app.py``) Flask application:

  100 Dashboard Overview      -> /discrepancies, /assignment-queue, /activity-feed
  101 Dashboard Transaction   -> /api/v1/metrics/merchants (+ imports)
  102 Dashboard Reconciliation-> /analytics/reconciliation-report, /analytics/sla-metrics
  103 Dashboard Risk          -> /analytics/operator-stats, /incidents/search
  104 Dashboard Integration    -> /providers (+ provider detail/connection/health)
  105 Dashboard Activity       -> /activity-feed
  106 Dashboard Usage          -> /organizations/<org_id>/usage
  107 Dashboard Security       -> /v1/settings (tenant-scoped read + write guard)
  108 Dashboard Production/Health -> /health (sanitized), /metrics (gated)

Every tenant-owned read here must be scoped to the *authenticated* tenant: data
belonging to tenant-b must never be readable by a caller authenticated as
tenant-a. The two unauthenticated surfaces (/health, /metrics) must not leak
dependency internals or metrics without a scrape token / read:metrics grant.

These tests replicate the established app_2 + real-AuthRBAC-token pattern from
test_dashboard_api.py and test_reporting_tenant_boundary.py (no bypassed auth --
Rule #12). All behaviours were verified against the running implementation first
(Rule #62). No production code is changed here.
"""

from __future__ import annotations

import importlib
import os
import tempfile
from datetime import datetime, timezone

import pytest
import auth_rbac
from auth_rbac import AuthRBAC


@pytest.fixture(scope="module")
def report_app():
    # Module-scoped: the app binds a module-level engine at import, so reload it
    # once and share the client across the suite (keeps runtime bounded).
    env_keys = (
        "DATABASE_URL",
        "PESAGUARD_API_AUTH_REQUIRED",
        "JWT_SECRET_KEY",
        "PESAGUARD_API_URL",
    )
    previous_env = {key: os.environ.get(key) for key in env_keys}
    previous_revocation_engine = auth_rbac._revocation_engine
    previous_revocation_session = auth_rbac._RevocationSession
    previous_revocation_checked = auth_rbac._revocation_store_checked
    dashboard_module = None
    try:
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = os.path.join(tmpdir, "dashboard-services.db")
            os.environ["DATABASE_URL"] = f"sqlite:///{db_path}"
            os.environ["PESAGUARD_API_AUTH_REQUIRED"] = "1"
            os.environ.setdefault("JWT_SECRET_KEY", "test-secret-key-with-at-least-32-bytes")
            os.environ.setdefault("PESAGUARD_API_URL", "https://api.pesaguard.test")

            import app_2

            dashboard_module = importlib.reload(app_2)
            dashboard_module.Base.metadata.create_all(dashboard_module.primary_engine)

            from action_audit import ActionAuditEntry, Base as AuditBase
            from auth_rbac import _RevocationBase
            from models import Discrepancy, MerchantMetric, PaymentProvider, Transaction, UserAccount

            AuditBase.metadata.create_all(dashboard_module.primary_engine)
            _RevocationBase.metadata.create_all(dashboard_module.primary_engine)
            ActionAuditEntry.__table__.create(dashboard_module.primary_engine, checkfirst=True)
            dashboard_module.app.config.update(TESTING=True)

            session = dashboard_module.SessionLocal()
            try:
                # tenant-a admin is the only authenticated identity in this suite.
                session.add(UserAccount(
                    id="tenant-a-user", tenant_id="tenant-a", username="a-user",
                    roles=["admin"], permissions=[], status="active", authorization_version=1,
                ))
                # tenant-b owns sensitive rows across every dashboard domain.
                session.add(Transaction(
                    trans_id="tx-b-secret", tenant_id="tenant-b", trans_amount="100.00",
                    msisdn="254700000001", business_short_code="600000",
                    trans_time="20260912120000", raw_payload={"TransID": "tx-b-secret"},
                ))
                session.add(Discrepancy(
                    id="disc-b-secret", trans_id="tx-b-secret", tenant_id="tenant-b",
                    anomaly_type="amount_mismatch", status="needs_review", severity="critical",
                    timeline=[{"event": "created"}],
                ))
                session.add(MerchantMetric(
                    tenant_id="tenant-b", merchant_id="merchant-b-secret", granularity="day",
                    bucket_start=datetime.now(timezone.utc), transaction_count=5,
                ))
                session.add(PaymentProvider(
                    id="provider_dead00beef00", tenant_id="tenant-b", name="provider-b-secret",
                    provider_type="payment", status="active",
                ))
                session.commit()
            finally:
                session.close()

            try:
                with dashboard_module.app.test_client() as client:
                    yield client
            finally:
                dashboard_module.primary_engine.dispose()
                current_revocation_engine = auth_rbac._revocation_engine
                auth_rbac.configure_revocation_store(
                    previous_revocation_engine,
                    previous_revocation_session,
                )
                auth_rbac._revocation_store_checked = previous_revocation_checked
                if (
                    current_revocation_engine is not None
                    and current_revocation_engine is not previous_revocation_engine
                ):
                    current_revocation_engine.dispose()
    finally:
        for key, value in previous_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def _token(roles=("admin",)):
    # Credentials MUST match the seeded DB row exactly; AuthRBAC rejects tokens
    # whose claims do not correspond to a live, current account (fail-closed).
    return AuthRBAC.generate_token(
        user_id="tenant-a-user", username="a-user", tenant_id="tenant-a", roles=list(roles),
    )


def _headers(roles=("admin",)):
    return {"Authorization": f"Bearer {_token(roles)}"}


_SECRETS = ("tx-b-secret", "merchant-b-secret", "provider-b-secret", "disc-b-secret")



def _assert_no_leak(response):
    body = response.get_data(as_text=True)
    for secret in _SECRETS:
        assert secret not in body, f"tenant-b data leaked: {secret}"



# --- Service 100: Dashboard Overview -------------------------------------------


def test_overview_discrepancies_scoped_to_authenticated_tenant(report_app):
    response = report_app.get("/discrepancies", headers=_headers())
    assert response.status_code == 200
    _assert_no_leak(response)


def test_overview_assignment_queue_scoped_to_authenticated_tenant(report_app):
    response = report_app.get("/assignment-queue", headers=_headers())
    assert response.status_code == 200
    _assert_no_leak(response)


def test_overview_requires_authentication(report_app):
    assert report_app.get("/discrepancies").status_code == 401


# --- Service 101: Dashboard Transaction ----------------------------------------


def test_merchant_metrics_scoped_to_authenticated_tenant(report_app):
    response = report_app.get("/api/v1/metrics/merchants?granularity=day", headers=_headers())
    assert response.status_code == 200
    assert response.get_json()["tenant_id"] == "tenant-a"
    _assert_no_leak(response)


def test_merchant_metrics_rejects_invalid_granularity(report_app):
    response = report_app.get("/api/v1/metrics/merchants?granularity=century", headers=_headers())
    assert response.status_code == 400


# --- Service 102: Dashboard Reconciliation -------------------------------------


def test_reconciliation_report_scoped_to_authenticated_tenant(report_app):
    response = report_app.get("/analytics/reconciliation-report", headers=_headers())
    assert response.status_code == 200
    _assert_no_leak(response)


def test_sla_metrics_scoped_to_authenticated_tenant(report_app):
    response = report_app.get("/analytics/sla-metrics", headers=_headers())
    assert response.status_code == 200
    _assert_no_leak(response)


# --- Service 103: Dashboard Risk -----------------------------------------------


def test_operator_stats_scoped_to_authenticated_tenant(report_app):
    response = report_app.get("/analytics/operator-stats", headers=_headers())
    assert response.status_code == 200
    _assert_no_leak(response)


def test_incident_search_scoped_to_authenticated_tenant(report_app):
    response = report_app.get("/incidents/search?query=secret", headers=_headers())
    assert response.status_code == 200
    _assert_no_leak(response)


# --- Service 104: Dashboard Integration ----------------------------------------


def test_provider_list_scoped_to_authenticated_tenant(report_app):
    response = report_app.get("/providers", headers=_headers())
    assert response.status_code == 200
    assert "provider-b-secret" not in response.get_data(as_text=True)


def test_provider_detail_denies_cross_tenant(report_app):
    # tenant-b's provider must be invisible to a tenant-a caller (404, not 200).
    response = report_app.get("/providers/provider_dead00beef00", headers=_headers())
    assert response.status_code == 404
    _assert_no_leak(response)


# --- Service 105: Dashboard Activity -------------------------------------------


def test_activity_feed_scoped_to_authenticated_tenant(report_app):
    response = report_app.get("/activity-feed", headers=_headers())
    assert response.status_code == 200
    _assert_no_leak(response)


# --- Service 106: Dashboard Usage ----------------------------------------------


def test_usage_summary_requires_authentication(report_app):
    assert report_app.get("/api/v1/organizations/org-a/usage").status_code == 401


def test_usage_summary_scoped_to_authenticated_tenant(report_app):
    # get_usage_summary is scoped by the *authenticated* tenant, so even when a
    # foreign organization id is supplied in the path, no tenant-b usage figures
    # are returned; the payload reflects tenant-a's scope only.
    response = report_app.get("/api/v1/organizations/org-b-secret/usage", headers=_headers())
    assert response.status_code == 200
    payload = response.get_json()
    assert payload["tenant_id"] == "tenant-a"
    _assert_no_leak(response)


# --- Service 107: Dashboard Security -------------------------------------------


def test_settings_read_scoped_to_authenticated_tenant(report_app):
    response = report_app.get("/v1/settings", headers=_headers())
    assert response.status_code == 200
    _assert_no_leak(response)


def test_settings_write_denies_cross_tenant(report_app):
    # A caller authenticated as tenant-a must not write tenant-b settings, even
    # when tenant_id is supplied in the request body.
    response = report_app.post(
        "/v1/settings",
        headers=_headers(),
        json={"tenant_id": "tenant-b", "preferred_locale": "en"},
    )
    assert response.status_code == 403
    assert response.get_json().get("error") == "tenant_access_denied"


# --- Service 108: Dashboard Production / Health --------------------------------


def test_health_sanitizes_dependency_details_for_anonymous_callers(report_app, monkeypatch):
    import app_2

    crafted = {
        "status": "failed",
        "service": "pesaguard",
        "checks": {
            "database": {"status": "failed", "error": "conn refused to 10.0.0.5:5432 db-internal-host"},
            "kafka": {"status": "failed", "error": "bootstrap 10.0.0.9:9092 unreachable"},
        },
    }
    monkeypatch.setattr(app_2, "build_health_payload", lambda: crafted)
    response = report_app.get("/health")  # no auth
    body = response.get_data(as_text=True)
    # Rule #18: unauthenticated callers must not receive dependency internals.
    assert "10.0.0.5" not in body and "db-internal-host" not in body
    assert "10.0.0.9" not in body
    checks = response.get_json()["checks"]
    assert checks["database"] == {"status": "failed"}
    assert "error" not in checks["database"]


def test_metrics_rejects_unauthenticated_callers(report_app):
    # No Prometheus scrape token and no read:metrics grant -> 401.
    assert report_app.get("/metrics").status_code == 401


def test_metrics_serves_authenticated_reader_without_tenant_leak(report_app):
    response = report_app.get("/metrics", headers=_headers())
    assert response.status_code == 200
    _assert_no_leak(response)
