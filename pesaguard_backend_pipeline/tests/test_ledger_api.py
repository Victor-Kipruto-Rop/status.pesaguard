"""API integration tests for the double-entry ledger endpoints.

Isolated SQLite per test, auth disabled (PESAGUARD_API_AUTH_REQUIRED=0) so the
permission decorators short-circuit and the tenant-scoping + financial logic is
what's under test. Covers: balanced post via HTTP, unbalanced rejection,
idempotent replay, tenant isolation, and derived balance. Mirrors the harness
in test_api_idempotency_contract.py.
"""

import importlib

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker


@pytest.fixture()
def ledger_client(tmp_path, monkeypatch):
    database_url = f"sqlite:///{tmp_path / 'ledger-api.db'}"
    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setenv("PESAGUARD_API_AUTH_REQUIRED", "0")
    monkeypatch.setenv("JWT_SECRET_KEY", "ledger-test-secret-012345678901234567890123")

    engine = create_engine(database_url)
    from models import Base

    Base.metadata.create_all(engine)

    import app as webhook_app

    webhook_app = importlib.reload(webhook_app)
    webhook_app.app.config.update(TESTING=True)
    webhook_app.event_store._ensure_ready()
    Base.metadata.create_all(webhook_app.event_store.engine)

    # Seed a tenant header; with auth disabled the authenticated tenant is empty
    # so the X-Tenant-ID header becomes the acting tenant.
    with webhook_app.app.test_client() as client:
        yield client


def _headers(tenant="tenant-a"):
    return {"X-Tenant-ID": tenant, "Content-Type": "application/json"}


def _make_ledger(client, slug="main"):
    return client.post("/api/v1/ledgers", headers=_headers(), json={"slug": slug, "name": "Main"})


def _make_account(client, ledger_id, code, normal="debit"):
    return client.post(
        f"/api/v1/ledgers/{ledger_id}/accounts",
        headers=_headers(),
        json={"code": code, "name": code.title(), "currency": "KES", "normal_balance": normal},
    )


def test_create_ledger_and_account(ledger_client):
    created = _make_ledger(ledger_client)
    assert created.status_code == 201, created.get_json()
    ledger_id = created.get_json()["id"]

    account = _make_account(ledger_client, ledger_id, "cash")
    assert account.status_code == 201, account.get_json()
    assert account.get_json()["normal_balance"] == "debit"

    revenue = _make_account(ledger_client, ledger_id, "revenue", normal="credit")
    assert revenue.status_code == 201


def test_post_balanced_transaction_via_api(ledger_client):
    ledger_id = _make_ledger(ledger_client).get_json()["id"]
    _make_account(ledger_client, ledger_id, "cash")
    _make_account(ledger_client, ledger_id, "revenue", normal="credit")

    resp = ledger_client.post(
        f"/api/v1/ledgers/{ledger_id}/transactions",
        headers=_headers(),
        json={
            "external_id": "ext-api-1",
            "entries": [
                {"account_code": "cash", "direction": "debit", "amount": "100.00"},
                {"account_code": "revenue", "direction": "credit", "amount": "100.00"},
            ],
        },
    )
    assert resp.status_code == 201, resp.get_json()
    body = resp.get_json()
    assert body["status"] == "posted"

    bal = ledger_client.get(
        f"/api/v1/ledgers/{ledger_id}/accounts/cash/balance", headers=_headers()
    )
    assert bal.status_code == 200
    assert bal.get_json()["balances_minor"] == {"KES": 10000}


def test_unbalanced_transaction_rejected(ledger_client):
    ledger_id = _make_ledger(ledger_client).get_json()["id"]
    _make_account(ledger_client, ledger_id, "cash")
    _make_account(ledger_client, ledger_id, "revenue", normal="credit")

    resp = ledger_client.post(
        f"/api/v1/ledgers/{ledger_id}/transactions",
        headers=_headers(),
        json={
            "external_id": "ext-bad",
            "entries": [
                {"account_code": "cash", "direction": "debit", "amount": "100.00"},
                {"account_code": "revenue", "direction": "credit", "amount": "50.00"},
            ],
        },
    )
    assert resp.status_code == 400, resp.get_json()


def test_idempotent_replay_via_api(ledger_client):
    ledger_id = _make_ledger(ledger_client).get_json()["id"]
    _make_account(ledger_client, ledger_id, "cash")
    _make_account(ledger_client, ledger_id, "revenue", normal="credit")

    payload = {
        "external_id": "ext-idem",
        "entries": [
            {"account_code": "cash", "direction": "debit", "amount": "25.00"},
            {"account_code": "revenue", "direction": "credit", "amount": "25.00"},
        ],
    }
    first = ledger_client.post(f"/api/v1/ledgers/{ledger_id}/transactions", headers=_headers(), json=payload)
    second = ledger_client.post(f"/api/v1/ledgers/{ledger_id}/transactions", headers=_headers(), json=payload)
    assert first.status_code == 201 and second.status_code == 201
    assert first.get_json()["id"] == second.get_json()["id"]


def test_cross_tenant_isolation(ledger_client):
    ledger_id = _make_ledger(ledger_client, slug="iso").get_json()["id"]
    _make_account(ledger_client, ledger_id, "cash")
    _make_account(ledger_client, ledger_id, "revenue", normal="credit")

    # Different tenant cannot post into tenant-a's ledger.
    resp = ledger_client.post(
        f"/api/v1/ledgers/{ledger_id}/transactions",
        headers=_headers(tenant="tenant-b"),
        json={
            "external_id": "ext-cross",
            "entries": [
                {"account_code": "cash", "direction": "debit", "amount": "10.00"},
                {"account_code": "revenue", "direction": "credit", "amount": "10.00"},
            ],
        },
    )
    assert resp.status_code == 404, resp.get_json()
