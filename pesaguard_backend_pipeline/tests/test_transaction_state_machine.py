"""Regression tests for the Transaction Status / Payment State Machine (services 13 & 17).

PesaGuard reconciles M-Pesa callbacks; it never moves money (see
docs/product/PRODUCT_SCOPE.md). The "state machine" that matters is therefore the
transaction *lifecycle* -- RECEIVED -> VALIDATED -> PROCESSING -> RECONCILING ->
RECONCILED, with FAILED/REJECTED branches -- enforced by lifecycle.py, mirrored by a
database CheckConstraint, and advanced only through EventStore.transition_transaction
under optimistic locking.

Before this file, test_lifecycle_integrity covered reconciliation, discrepancy and
data-lifecycle transitions but NOT the transaction state machine, and no test proved
that a stale/concurrent version is rejected. These tests close that gap without
changing any production behaviour. Every assertion is a pure invariant check
(Rule #26: lifecycle decisions must be explainable and deterministic).
"""

from __future__ import annotations

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from event_store import EventStore, ProcessResult
from lifecycle import (
    InvalidLifecycleTransition,
    TRANSACTION_STATES,
    transition_transaction,
)
from models import Base, Transaction, TransactionEvent


# --- Pure unit tests: the transition graph itself (service 17) ------------------


def test_transaction_states_are_the_documented_lifecycle():
    # Keeps the vocabulary pinned so a silent rename cannot weaken the DB check
    # constraint or the HTTP status contract.
    assert TRANSACTION_STATES == (
        "RECEIVED",
        "VALIDATED",
        "PROCESSING",
        "RECONCILING",
        "RECONCILED",
        "FAILED",
        "REJECTED",
    )


def test_happy_path_advances_one_stage_at_a_time():
    assert transition_transaction("RECEIVED", "VALIDATED") == "VALIDATED"
    assert transition_transaction("VALIDATED", "PROCESSING") == "PROCESSING"
    assert transition_transaction("PROCESSING", "RECONCILING") == "RECONCILING"
    assert transition_transaction("RECONCILING", "RECONCILED") == "RECONCILED"


def test_terminal_states_are_terminal():
    # RECONCILED and REJECTED have no outgoing edges: a settled or rejected
    # transaction must never be silently advanced again.
    for terminal in ("RECONCILED", "REJECTED"):
        for target in TRANSACTION_STATES:
            if target == terminal:
                continue
            with pytest.raises(InvalidLifecycleTransition):
                transition_transaction(terminal, target)


def test_skipping_a_stage_is_rejected():
    # Forward-only, exactly one stage: RECEIVED cannot jump straight to PROCESSING.
    with pytest.raises(InvalidLifecycleTransition):
        transition_transaction("RECEIVED", "PROCESSING")
    with pytest.raises(InvalidLifecycleTransition):
        transition_transaction("VALIDATED", "RECONCILED")


def test_regression_is_rejected():
    # A transaction that has moved forward cannot be dragged back to an earlier
    # stage; this protects reconciliation determinism (Rule #26).
    with pytest.raises(InvalidLifecycleTransition):
        transition_transaction("PROCESSING", "VALIDATED")
    with pytest.raises(InvalidLifecycleTransition):
        transition_transaction("RECONCILING", "RECEIVED")


def test_failure_is_reachable_from_any_active_stage_but_only_retries_into_processing():
    # Any in-flight stage may fail; the only recovery edge out of FAILED is back
    # into PROCESSING (a deliberate retry), never a skip to RECONCILED.
    for stage in ("RECEIVED", "VALIDATED", "PROCESSING", "RECONCILING"):
        assert transition_transaction(stage, "FAILED") == "FAILED"
    assert transition_transaction("FAILED", "PROCESSING") == "PROCESSING"
    with pytest.raises(InvalidLifecycleTransition):
        transition_transaction("FAILED", "RECONCILED")
    with pytest.raises(InvalidLifecycleTransition):
        transition_transaction("FAILED", "VALIDATED")


def test_unknown_state_or_target_is_rejected():
    with pytest.raises(InvalidLifecycleTransition):
        transition_transaction("NOT_A_STATE", "VALIDATED")
    with pytest.raises(InvalidLifecycleTransition):
        transition_transaction("RECEIVED", "NOT_A_STATE")


# --- Integration tests: EventStore.transition_transaction (services 13 & 17) -----


def _payload(trans_id: str) -> dict:
    return {
        "TransID": trans_id,
        "TransAmount": "10.50",
        "MSISDN": "254700000000",
        "BusinessShortCode": "123456",
        "TransTime": "20240101120000",
    }


@pytest.fixture()
def store(tmp_path):
    database_url = f"sqlite:///{tmp_path / 'state-machine.db'}"
    engine = create_engine(database_url)
    Base.metadata.create_all(engine)
    event_store = EventStore(database_url=database_url)
    yield event_store, database_url, engine
    event_store.engine.dispose()
    engine.dispose()


def _seed(store: EventStore, trans_id: str, tenant_id: str = "tenant-a"):
    assert store.mark_processed(_payload(trans_id), tenant_id=tenant_id) is ProcessResult.STORED


def _status(database_url: str, trans_id: str, tenant_id: str = "tenant-a"):
    engine = create_engine(database_url)
    Session = sessionmaker(bind=engine)
    try:
        with Session() as session:
            row = session.query(Transaction).filter_by(trans_id=trans_id, tenant_id=tenant_id).one()
            return row.status, int(row.version)
    finally:
        engine.dispose()


def test_seeded_transaction_starts_received_at_version_one(store):
    event_store, database_url, _ = store
    _seed(event_store, "sm-1")
    assert _status(database_url, "sm-1") == ("RECEIVED", 1)


def test_transition_advances_state_bumps_version_and_appends_event(store):
    event_store, database_url, engine = store
    _seed(event_store, "sm-2")

    assert event_store.transition_transaction("sm-2", "VALIDATED", "tenant-a", expected_version=1)
    assert _status(database_url, "sm-2") == ("VALIDATED", 2)

    Session = sessionmaker(bind=engine)
    with Session() as session:
        # mark_processed already appended the initial persist event, so the log is
        # append-only with exactly one state-change record on top of it.
        state_changes = (
            session.query(TransactionEvent)
            .filter_by(trans_id="sm-2", tenant_id="tenant-a", event_type="transaction.state_changed")
            .all()
        )
    assert len(state_changes) == 1
    assert state_changes[0].from_state == "RECEIVED"
    assert state_changes[0].to_state == "VALIDATED"


def test_full_happy_path_reaches_reconciled(store):
    event_store, database_url, _ = store
    _seed(event_store, "sm-3")
    version = 1
    for target in ("VALIDATED", "PROCESSING", "RECONCILING", "RECONCILED"):
        assert event_store.transition_transaction("sm-3", target, "tenant-a", expected_version=version)
        version += 1
    assert _status(database_url, "sm-3") == ("RECONCILED", 5)


def test_illegal_transition_is_rejected_and_state_is_unchanged(store):
    event_store, database_url, _ = store
    _seed(event_store, "sm-4")
    # RECEIVED -> PROCESSING skips a stage and must be refused without mutating.
    assert event_store.transition_transaction("sm-4", "PROCESSING", "tenant-a", expected_version=1) is False
    assert _status(database_url, "sm-4") == ("RECEIVED", 1)


def test_stale_version_is_rejected_optimistic_locking(store):
    # Two workers read version=1. The first advances it; the second, still holding
    # the stale expected_version, must lose the race and change nothing (Rule #8/#9).
    event_store, database_url, _ = store
    _seed(event_store, "sm-5")

    assert event_store.transition_transaction("sm-5", "VALIDATED", "tenant-a", expected_version=1) is True
    # Replaying the same stale version is a lost update and must be refused.
    assert event_store.transition_transaction("sm-5", "VALIDATED", "tenant-a", expected_version=1) is False
    assert _status(database_url, "sm-5") == ("VALIDATED", 2)


def test_terminal_state_cannot_be_advanced(store):
    event_store, database_url, _ = store
    _seed(event_store, "sm-6")
    version = 1
    for target in ("VALIDATED", "PROCESSING", "RECONCILING", "RECONCILED"):
        assert event_store.transition_transaction("sm-6", target, "tenant-a", expected_version=version)
        version += 1
    # RECONCILED is terminal: any further move is refused.
    assert event_store.transition_transaction("sm-6", "PROCESSING", "tenant-a", expected_version=version) is False
    assert _status(database_url, "sm-6") == ("RECONCILED", 5)


def test_transition_is_tenant_scoped(store):
    # A transition attempted under the wrong tenant must not touch the record.
    event_store, database_url, _ = store
    _seed(event_store, "sm-7", tenant_id="tenant-a")

    assert event_store.transition_transaction("sm-7", "VALIDATED", "tenant-b", expected_version=1) is False
    assert _status(database_url, "sm-7", tenant_id="tenant-a") == ("RECEIVED", 1)


def test_unknown_transaction_transition_is_rejected(store):
    event_store, database_url, _ = store
    assert event_store.transition_transaction("does-not-exist", "VALIDATED", "tenant-a", expected_version=1) is False

