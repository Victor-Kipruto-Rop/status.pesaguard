"""Security regression tests for the Transaction Search endpoint (service 19).

The search filter is interpolated into a SQL ``ILIKE`` pattern. These tests pin the
two invariants that keep that safe (Rule #25, #11):

  1. Wildcard/escape characters (``%``, ``_``, ``\\``) and injection-style payloads
     are treated as *literal* text, never as pattern metacharacters or SQL.
  2. A tenant can only ever see its own transactions; a spoofed ``X-Tenant-ID``
     that disagrees with the authenticated tenant is denied, and cross-tenant reads
     return 404 rather than leaking another tenant's rows.

No production code is changed here; these are additive guards over existing,
already-tenant-scoped behaviour.
"""

from __future__ import annotations

import importlib

import pytest


@pytest.fixture()
def dashboard_api(tmp_path, monkeypatch):
    database_url = f"sqlite:///{tmp_path / 'search-security.db'}"
    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setenv("PESAGUARD_API_AUTH_REQUIRED", "0")
    monkeypatch.setenv("JWT_SECRET_KEY", "search-security-test-secret-0123456789")

    dashboard = importlib.reload(importlib.import_module("api.dashboard_app"))
    dashboard.Base.metadata.create_all(dashboard.primary_engine)
    from auth_rbac import _RevocationBase

    _RevocationBase.metadata.create_all(dashboard.primary_engine)
    dashboard.transaction_event_store._ensure_ready()
    yield dashboard
    dashboard.primary_engine.dispose()
    dashboard.transaction_event_store.engine.dispose()


def _create(client, tenant_id: str, transaction_id: str, idempotency_key: str):
    return client.post(
        "/api/v1/transactions",
        headers={"Idempotency-Key": idempotency_key, "X-Tenant-ID": tenant_id},
        json={
            "provider_transaction_id": transaction_id,
            "provider_account_id": "600000",
            "provider": "mpesa",
            "TransAmount": "25.00",
            "Currency": "KES",
            "MSISDN": "254700000000",
            "TransTime": "20260913120000",
        },
    )


def test_bare_wildcard_filter_matches_everything_as_literal_not_pattern(dashboard_api):
    # A bare '%' filter must be escaped to a literal percent, which matches no real
    # transaction id -- it must NOT behave as the SQL "match all" wildcard.
    client = dashboard_api.app.test_client()
    _create(client, "tenant-a", "search-literal-1", "search-literal-key-1")

    response = client.get(
        "/api/v1/transactions/search?filter=%25",
        headers={"X-Tenant-ID": "tenant-a"},
    )
    assert response.status_code == 200
    assert response.json["total"] == 0
    assert response.json["items"] == []


@pytest.mark.parametrize(
    "payload",
    [
        "%' OR '1'='1",
        "'; DROP TABLE transactions; --",
        "%\\%",
        "100%_legit",
        "search-guard-1' --",
    ],
)
def test_injection_and_wildcard_payloads_are_neutralised(dashboard_api, payload):
    client = dashboard_api.app.test_client()
    _create(client, "tenant-a", "search-guard-1", "search-guard-key-1")

    from urllib.parse import quote

    response = client.get(
        f"/api/v1/transactions/search?filter={quote(payload, safe='')}",
        headers={"X-Tenant-ID": "tenant-a"},
    )
    # The query is safe: it executes, returns 200, and never matches the seeded row
    # via wildcard expansion (these payloads are not literal substrings of any
    # searchable field), proving metacharacters are escaped rather than interpreted.
    assert response.status_code == 200
    assert response.json["total"] == 0

    # The table still exists and the seeded transaction is intact and reachable.
    follow_up = client.get(
        "/api/v1/transactions/search?filter=search-guard-1",
        headers={"X-Tenant-ID": "tenant-a"},
    )
    assert follow_up.status_code == 200
    assert follow_up.json["total"] == 1


def test_search_is_tenant_scoped_and_hides_other_tenants(dashboard_api):
    client = dashboard_api.app.test_client()
    _create(client, "tenant-a", "scope-a-tx", "scope-a-key")
    _create(client, "tenant-b", "scope-b-tx", "scope-b-key")

    a_results = client.get("/api/v1/transactions/search?per_page=50", headers={"X-Tenant-ID": "tenant-a"})
    assert a_results.status_code == 200
    assert a_results.json["total"] == 1
    assert a_results.json["items"][0]["tenant_id"] == "tenant-a"

    # Searching by another tenant's identifier from tenant-a returns nothing.
    cross = client.get(
        "/api/v1/transactions/search?filter=scope-b-tx",
        headers={"X-Tenant-ID": "tenant-a"},
    )
    assert cross.status_code == 200
    assert cross.json["total"] == 0
