"""Unit tests for the double-entry ledger service.

Isolated SQLite database per test (Base.metadata.create_all); no network,
no Docker, no external services. Covers the Modern Treasury-grade guarantees:
balanced-per-currency, atomic, idempotent, immutable-once-posted, tenant
isolation, optimistic lock-version conflicts, and minor-unit precision.
"""

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from double_entry_ledger import (
    LedgerConflictError,
    LedgerEntryInput,
    LedgerError,
    LedgerNotFoundError,
    LedgerStateError,
    LedgerUnbalancedError,
    account_balance,
    get_transaction,
    post_transaction,
    to_minor_units,
    void_pending_transaction,
)
from models import Base, Ledger, LedgerAccount, LedgerTransaction


@pytest.fixture()
def session(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'ledger.db'}")
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    db = factory()
    try:
        yield db
    finally:
        db.close()


@pytest.fixture()
def books(session):
    ledger = Ledger(tenant_id="tenant-a", slug="main", name="Main Ledger")
    session.add(ledger)
    session.flush()
    cash = LedgerAccount(
        tenant_id="tenant-a",
        ledger_id=ledger.id,
        code="cash",
        name="Cash",
        currency="KES",
        normal_balance="debit",
    )
    revenue = LedgerAccount(
        tenant_id="tenant-a",
        ledger_id=ledger.id,
        code="revenue",
        name="Revenue",
        currency="KES",
        normal_balance="credit",
    )
    session.add_all([cash, revenue])
    session.commit()
    return ledger


def _entries(amount=10000):
    return [
        LedgerEntryInput(account_code="cash", direction="debit", amount_minor=amount),
        LedgerEntryInput(account_code="revenue", direction="credit", amount_minor=amount),
    ]


def test_post_balanced_transaction(session, books):
    txn = post_transaction(
        session,
        tenant_id="tenant-a",
        ledger_id=books.id,
        external_id="ext-1",
        entries=_entries(),
    )
    session.commit()
    assert txn.status == "posted"
    assert txn.posted_at is not None
    fetched = get_transaction(session, tenant_id="tenant-a", transaction_id=txn.id)
    assert len(fetched["entries"]) == 2
    assert fetched["external_id"] == "ext-1"


def test_unbalanced_entries_rejected(session, books):
    with pytest.raises(LedgerUnbalancedError):
        post_transaction(
            session,
            tenant_id="tenant-a",
            ledger_id=books.id,
            external_id="ext-bad",
            entries=[
                LedgerEntryInput(account_code="cash", direction="debit", amount_minor=10000),
                LedgerEntryInput(account_code="revenue", direction="credit", amount_minor=9999),
            ],
        )


def test_single_entry_rejected(session, books):
    with pytest.raises(LedgerUnbalancedError):
        post_transaction(
            session,
            tenant_id="tenant-a",
            ledger_id=books.id,
            external_id="ext-one",
            entries=[LedgerEntryInput(account_code="cash", direction="debit", amount_minor=100)],
        )


def test_idempotent_replay_returns_original(session, books):
    first = post_transaction(
        session, tenant_id="tenant-a", ledger_id=books.id,
        external_id="ext-idem", entries=_entries(),
    )
    session.commit()
    second = post_transaction(
        session, tenant_id="tenant-a", ledger_id=books.id,
        external_id="ext-idem", entries=_entries(),
    )
    assert second.id == first.id
    count = session.query(LedgerTransaction).filter(
        LedgerTransaction.tenant_id == "tenant-a",
        LedgerTransaction.external_id == "ext-idem",
    ).count()
    assert count == 1


def test_balance_derived_from_posted_entries(session, books):
    post_transaction(
        session, tenant_id="tenant-a", ledger_id=books.id,
        external_id="ext-bal", entries=_entries(25000),
    )
    session.commit()
    cash = account_balance(session, tenant_id="tenant-a", ledger_id=books.id, account_code="cash")
    revenue = account_balance(session, tenant_id="tenant-a", ledger_id=books.id, account_code="revenue")
    assert cash["balances_minor"] == {"KES": 25000}
    assert revenue["balances_minor"] == {"KES": 25000}
    assert cash["lock_version"] == 2


def test_cross_tenant_isolation(session, books):
    with pytest.raises(LedgerNotFoundError):
        post_transaction(
            session, tenant_id="tenant-b", ledger_id=books.id,
            external_id="ext-x", entries=_entries(),
        )


def test_unknown_account_rejected(session, books):
    with pytest.raises(LedgerNotFoundError):
        post_transaction(
            session, tenant_id="tenant-a", ledger_id=books.id,
            external_id="ext-missing",
            entries=[
                LedgerEntryInput(account_code="cash", direction="debit", amount_minor=100),
                LedgerEntryInput(account_code="ghost", direction="credit", amount_minor=100),
            ],
        )


def test_stale_lock_version_conflicts(session, books):
    before = account_balance(session, tenant_id="tenant-a", ledger_id=books.id, account_code="cash")
    post_transaction(
        session, tenant_id="tenant-a", ledger_id=books.id,
        external_id="ext-bump", entries=_entries(500),
    )
    session.commit()
    with pytest.raises(LedgerConflictError):
        post_transaction(
            session, tenant_id="tenant-a", ledger_id=books.id,
            external_id="ext-stale", entries=_entries(500),
            expected_lock_versions={"cash": before["lock_version"], "revenue": 1},
        )


def test_posted_transaction_cannot_be_voided(session, books):
    txn = post_transaction(
        session, tenant_id="tenant-a", ledger_id=books.id,
        external_id="ext-lock", entries=_entries(),
    )
    session.commit()
    with pytest.raises(LedgerStateError):
        void_pending_transaction(session, tenant_id="tenant-a", transaction_id=txn.id)


def test_minor_unit_precision():
    assert to_minor_units("100.00", "KES") == 10000
    assert to_minor_units("100", "UGX") == 100
    with pytest.raises(LedgerError):
        to_minor_units("100.001", "KES")
    with pytest.raises(LedgerError):
        to_minor_units("0", "KES")
    with pytest.raises(LedgerError):
        to_minor_units("-5", "KES")


def test_multi_currency_must_balance_per_currency(session, books):
    with pytest.raises(LedgerUnbalancedError):
        post_transaction(
            session, tenant_id="tenant-a", ledger_id=books.id,
            external_id="ext-fx",
            entries=[
                LedgerEntryInput(account_code="cash", direction="debit", amount_minor=10000, currency="KES"),
                LedgerEntryInput(account_code="revenue", direction="credit", amount_minor=10000, currency="USD"),
            ],
        )
