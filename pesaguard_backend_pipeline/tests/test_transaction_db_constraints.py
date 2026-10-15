"""Defense-in-depth: the database CheckConstraints backstopping the Transaction
table (services 12, 13, 17 & 20).

The Transaction lifecycle FSM is enforced in the application layer by
``lifecycle.transition_transaction`` (see ``test_transaction_state_machine.py``),
and its docstring states the FSM is "mirrored by a database CheckConstraint".
However, application validation alone is not a sufficient guard (Rule #23: do
NOT rely exclusively on application-level validation for critical integrity
rules). Any future writer -- a new route, a bulk migration, a manual fix, or a
regression that removes the FSM call -- could persist an illegal state unless the
database itself rejects it.

Before this file, NO test in the suite ever raised ``IntegrityError`` from the
``transactions`` table, so these database backstops were unpinned: a future
migration that dropped or weakened a constraint would not be caught. These tests
close that gap by writing directly to the database (bypassing the application
FSM) and asserting each constraint fires and nothing is partially committed.
They change no production behaviour (Rule #45).
"""

from __future__ import annotations

from decimal import Decimal

import pytest
from sqlalchemy import create_engine
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import sessionmaker

from models import Base, Transaction


@pytest.fixture()
def engine(tmp_path):
    database_url = f"sqlite:///{tmp_path / 'constraints.db'}"
    eng = create_engine(database_url)
    Base.metadata.create_all(eng)
    yield eng
    eng.dispose()


def _row(**overrides) -> Transaction:
    """A minimal otherwise-valid transaction row; callers flip one field."""
    values = dict(
        id=f"txn_{id(overrides)}",
        trans_id="db-constraint-tx",
        tenant_id="tenant-a",
        provider_account_id="600000",
        provider="mpesa",
        trans_amount=Decimal("10.50"),
        currency="KES",
        msisdn="254700000000",
        business_short_code="123456",
        trans_time="20240101120000",
        raw_payload={"TransID": "db-constraint-tx"},
        status="RECEIVED",
    )
    values.update(overrides)
    return Transaction(**values)


def _insert(engine, row: Transaction) -> None:
    session = sessionmaker(bind=engine)()
    try:
        session.add(row)
        session.commit()
    finally:
        session.close()


def _count(engine) -> int:
    session = sessionmaker(bind=engine)()
    try:
        return session.query(Transaction).count()
    finally:
        session.close()


# --- Status CheckConstraint: the DB mirror of the FSM (services 13 & 17) --------


def test_database_rejects_a_status_outside_the_lifecycle(engine):
    # Bypass the application FSM entirely: a status that is not a documented
    # lifecycle state must be rejected by the database, not silently stored.
    with pytest.raises(IntegrityError):
        _insert(engine, _row(id="txn_bad_status", status="NOT_A_STATE"))
    assert _count(engine) == 0


@pytest.mark.parametrize(
    "legal_status",
    ["RECEIVED", "VALIDATED", "PROCESSING", "RECONCILING", "RECONCILED", "FAILED", "REJECTED"],
)
def test_database_accepts_every_documented_lifecycle_status(engine, legal_status):
    # The complement of the test above: each legal lifecycle state must remain
    # persistable, so the constraint pins the vocabulary exactly and cannot drift.
    _insert(engine, _row(id=f"txn_ok_{legal_status}", status=legal_status))
    assert _count(engine) == 1


def test_lowercase_status_is_rejected_enforcing_the_uppercase_contract(engine):
    # The FSM vocabulary is uppercase; a lowercase variant must not sneak in.
    with pytest.raises(IntegrityError):
        _insert(engine, _row(id="txn_lower_status", status="reconciled"))
    assert _count(engine) == 0


# --- Financial-integrity CheckConstraints on the same table --------------------


def test_database_rejects_a_non_positive_amount(engine):
    # trans_amount > 0. A zero or negative amount must never be persisted
    # (Rule #7: this is the invariant that prevents a phantom/duplicate amount).
    for bad_amount in (Decimal("0"), Decimal("-1.00")):
        with pytest.raises(IntegrityError):
            _insert(engine, _row(id=f"txn_amt_{bad_amount}", trans_amount=bad_amount))
    assert _count(engine) == 0


def test_database_rejects_a_non_iso_currency(engine):
    # length(currency) = 3 AND currency = upper(currency).
    with pytest.raises(IntegrityError):
        _insert(engine, _row(id="txn_currency_short", currency="US"))
    with pytest.raises(IntegrityError):
        _insert(engine, _row(id="txn_currency_lower", currency="kes"))
    assert _count(engine) == 0


def test_database_rejects_an_empty_tenant_id(engine):
    # tenant_id IS NOT NULL AND tenant_id <> ''. Tenant isolation (Rule #11) is
    # backstopped at the DB layer: an anonymous/blank-tenant row is impossible.
    with pytest.raises(IntegrityError):
        _insert(engine, _row(id="txn_empty_tenant", tenant_id=""))
    assert _count(engine) == 0
