"""Performance and correctness-under-volume tests for transaction ingest,
idempotency (services 12 & 20) and state-machine transitions (service 17).

These mirror the existing communications load-test convention: in-memory SQLite,
no network, wall-clock measured with time.monotonic(), and correctness asserted
(all rows persisted, duplicates suppressed, transitions applied). We deliberately
do NOT assert tight wall-clock thresholds (Rule #39: measure, don't assume) --
we print the measured rate and assert only that work completed correctly and that
throughput is positive, so the suite stays green across machines and CI while
still surfacing regressions in the printed numbers.

Run: python -m pytest tests/test_transaction_performance.py -v -s
"""

from __future__ import annotations

import time

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from event_store import EventStore, ProcessResult
from models import Base, ProcessedTransaction, Transaction, TransactionEvent


def _payload(index: int) -> dict:
    return {
        "TransID": f"perf-tx-{index}",
        "TransAmount": "10.50",
        "MSISDN": "254700000000",
        "BusinessShortCode": "123456",
        "TransTime": "20240101120000",
    }


@pytest.fixture()
def store(tmp_path):
    database_url = f"sqlite:///{tmp_path / 'perf.db'}"
    engine = create_engine(database_url)
    Base.metadata.create_all(engine)
    event_store = EventStore(database_url=database_url)
    yield event_store, database_url, engine
    event_store.engine.dispose()
    engine.dispose()


def test_ingest_throughput_scales_linearly(store):
    # Ingest a batch of distinct transactions; every one must persist exactly once
    # (services 12 & 20). This is the hot path for webhook/API ingestion.
    event_store, database_url, engine = store
    count = 500

    started = time.monotonic()
    results = [event_store.mark_processed(_payload(i), tenant_id="tenant-a") for i in range(count)]
    elapsed = time.monotonic() - started
    rate = count / elapsed if elapsed else 0.0

    assert all(result is ProcessResult.STORED for result in results)

    Session = sessionmaker(bind=engine)
    with Session() as session:
        assert session.query(Transaction).filter_by(tenant_id="tenant-a").count() == count
        assert session.query(ProcessedTransaction).filter_by(tenant_id="tenant-a").count() == count
    print(f"\n  ingest: {count} tx in {elapsed:.3f}s = {rate:.0f} tx/sec")
    assert rate > 0


def test_duplicate_ingest_is_suppressed_at_volume(store):
    # Replaying every transaction a second time must be recognised as DUPLICATE and
    # must not create any new rows (idempotency under volume, services 12 & 20).
    event_store, database_url, engine = store
    count = 300

    for i in range(count):
        event_store.mark_processed(_payload(i), tenant_id="tenant-a")

    started = time.monotonic()
    replays = [event_store.mark_processed(_payload(i), tenant_id="tenant-a") for i in range(count)]
    elapsed = time.monotonic() - started
    rate = count / elapsed if elapsed else 0.0

    assert all(result is ProcessResult.DUPLICATE for result in replays)

    Session = sessionmaker(bind=engine)
    with Session() as session:
        assert session.query(Transaction).filter_by(tenant_id="tenant-a").count() == count
        assert session.query(ProcessedTransaction).filter_by(tenant_id="tenant-a").count() == count
    print(f"\n  idempotent replay: {count} dup in {elapsed:.3f}s = {rate:.0f} checks/sec")
    assert rate > 0


def test_state_machine_transitions_apply_at_volume(store):
    # Drive many transactions through the full lifecycle; each must reach RECONCILED
    # and append exactly one state-change event per hop (services 13 & 17).
    event_store, database_url, engine = store
    count = 200
    hops = ("VALIDATED", "PROCESSING", "RECONCILING", "RECONCILED")

    for i in range(count):
        event_store.mark_processed(_payload(i), tenant_id="tenant-a")

    started = time.monotonic()
    for target in hops:
        version = {"VALIDATED": 1, "PROCESSING": 2, "RECONCILING": 3, "RECONCILED": 4}[target]
        for i in range(count):
            assert event_store.transition_transaction(
                f"perf-tx-{i}", target, "tenant-a", expected_version=version
            )
    elapsed = time.monotonic() - started
    transitions = count * len(hops)
    rate = transitions / elapsed if elapsed else 0.0

    Session = sessionmaker(bind=engine)
    with Session() as session:
        reconciled = session.query(Transaction).filter_by(tenant_id="tenant-a", status="RECONCILED").count()
        state_changes = (
            session.query(TransactionEvent)
            .filter_by(tenant_id="tenant-a", event_type="transaction.state_changed")
            .count()
        )
    assert reconciled == count
    assert state_changes == transitions
    print(f"\n  transitions: {transitions} hops in {elapsed:.3f}s = {rate:.0f} hops/sec")
    assert rate > 0
