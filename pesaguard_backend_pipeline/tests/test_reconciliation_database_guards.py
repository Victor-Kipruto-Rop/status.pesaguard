from __future__ import annotations

import importlib.util
from decimal import Decimal
from pathlib import Path

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import create_engine, inspect, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import sessionmaker


MIGRATION_PATH = (
    Path(__file__).parents[1]
    / "alembic"
    / "versions"
    / "20261014_enforce_reconciliation_integrity.py"
)
CUSTOMER_MIGRATION_PATH = (
    Path(__file__).parents[1]
    / "alembic"
    / "versions"
    / "20261013_customer_account_ledger.py"
)


def _load_migration():
    spec = importlib.util.spec_from_file_location("enforce_reconciliation_integrity", MIGRATION_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _load_customer_migration():
    spec = importlib.util.spec_from_file_location("customer_account_ledger", CUSTOMER_MIGRATION_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_customer_ledger_revision_has_reversible_schema():
    engine = create_engine("sqlite:///:memory:")
    with engine.begin() as conn:
        migration_context = MigrationContext.configure(conn)
        with Operations.context(migration_context):
            _load_customer_migration().upgrade()
        tables = set(inspect(conn).get_table_names())
        assert {"customers", "customer_accounts", "account_balances", "account_relationships"} <= tables

        with Operations.context(MigrationContext.configure(conn)):
            _load_customer_migration().downgrade()
        assert not (
            {"customers", "customer_accounts", "account_balances", "account_relationships"}
            & set(inspect(conn).get_table_names())
        )
    engine.dispose()


@pytest.fixture()
def connection():
    engine = create_engine("sqlite:///:memory:")
    with engine.begin() as conn:
        conn.exec_driver_sql("CREATE TABLE transactions (id TEXT PRIMARY KEY, status TEXT NOT NULL)")
        conn.exec_driver_sql(
            "CREATE TABLE processed_transactions (id TEXT PRIMARY KEY, reconciliation_status TEXT NOT NULL)"
        )
        conn.exec_driver_sql(
            "CREATE TABLE reconciliation_matches (id TEXT PRIMARY KEY, status TEXT NOT NULL)"
        )
        migration_context = MigrationContext.configure(conn)
        with Operations.context(migration_context):
            _load_migration().upgrade()
        yield conn
    engine.dispose()


def test_database_enforces_transaction_lifecycle_edges(connection):
    connection.execute(text("INSERT INTO transactions (id, status) VALUES ('tx-1', 'RECEIVED')"))
    connection.execute(text("UPDATE transactions SET status='VALIDATED' WHERE id='tx-1'"))
    assert connection.execute(text("SELECT status FROM transactions WHERE id='tx-1'")).scalar_one() == "VALIDATED"

    with pytest.raises(IntegrityError, match="invalid transaction lifecycle transition"):
        connection.execute(text("UPDATE transactions SET status='RECONCILED' WHERE id='tx-1'"))

    connection.execute(text("UPDATE transactions SET status='FAILED' WHERE id='tx-1'"))
    connection.execute(text("UPDATE transactions SET status='PROCESSING' WHERE id='tx-1'"))
    assert connection.execute(text("SELECT status FROM transactions WHERE id='tx-1'")).scalar_one() == "PROCESSING"


def test_database_enforces_reconciliation_lifecycle_edges(connection):
    connection.execute(
        text("INSERT INTO processed_transactions (id, reconciliation_status) VALUES ('pt-1', 'pending')")
    )
    connection.execute(
        text("UPDATE processed_transactions SET reconciliation_status='processing' WHERE id='pt-1'")
    )
    connection.execute(
        text("UPDATE processed_transactions SET reconciliation_status='completed' WHERE id='pt-1'")
    )

    with pytest.raises(IntegrityError, match="invalid reconciliation lifecycle transition"):
        connection.execute(
            text("UPDATE processed_transactions SET reconciliation_status='processing' WHERE id='pt-1'")
        )


def test_reconciliation_decisions_are_append_only_at_database_boundary(connection):
    connection.execute(text("INSERT INTO reconciliation_matches VALUES ('match-1', 'MATCHED')"))
    with pytest.raises(IntegrityError, match="reconciliation decisions are append-only"):
        connection.execute(
            text("UPDATE reconciliation_matches SET status='MISMATCH' WHERE id='match-1'")
        )
    with pytest.raises(IntegrityError, match="reconciliation decisions are append-only"):
        connection.execute(text("DELETE FROM reconciliation_matches WHERE id='match-1'"))
    assert connection.execute(text("SELECT status FROM reconciliation_matches WHERE id='match-1'")).scalar_one() == "MATCHED"


def test_migration_downgrade_removes_database_guards(connection):
    with Operations.context(MigrationContext.configure(connection)):
        _load_migration().downgrade()
    connection.execute(text("INSERT INTO transactions (id, status) VALUES ('tx-down', 'RECEIVED')"))
    connection.execute(text("UPDATE transactions SET status='RECONCILED' WHERE id='tx-down'"))
    assert connection.execute(text("SELECT status FROM transactions WHERE id='tx-down'")).scalar_one() == "RECONCILED"


def test_atomic_reconciliation_persistence_obeys_database_lifecycle_triggers(tmp_path, monkeypatch):
    from action_audit import Base as AuditBase
    from event_store import EventStore, ProcessResult
    from models import Base
    import reconciliation_job

    engine = create_engine(f"sqlite:///{tmp_path / 'reconciliation-guards.db'}")
    Base.metadata.create_all(engine)
    AuditBase.metadata.create_all(engine)
    with engine.begin() as conn:
        migration_context = MigrationContext.configure(conn)
        with Operations.context(migration_context):
            _load_migration().upgrade()

    monkeypatch.setattr(
        reconciliation_job,
        "AuditSession",
        sessionmaker(bind=engine, expire_on_commit=False),
    )
    event = {
        "TransID": "guarded-tx",
        "TransAmount": "10.00",
        "MSISDN": "254700000000",
        "BusinessShortCode": "123456",
        "TransTime": "20260912120000",
    }
    store = EventStore(database_url=f"sqlite:///{tmp_path / 'reconciliation-guards.db'}")
    assert store.mark_processed(event, tenant_id="tenant-a") is ProcessResult.STORED
    evaluation = {
        "status": "matched",
        "phase3_status": "MATCHED",
        "severity": "info",
        "match": {"match_type": "exact"},
        "anomalies": [],
        "event": event,
        "tenant_id": "tenant-a",
        "evidence": {
            "matched_record": {"internal_ref": "ledger-1", "amount": Decimal("10.00")},
            "matching_rules": ["reference_exact", "amount_exact"],
            "match_score": 1.0,
            "match_timestamp": "2026-09-12T12:00:00+00:00",
            "engine_version": "phase3.3",
            "rule_version": "phase3.3",
        },
    }

    assert reconciliation_job._persist_atomically(
        event,
        evaluation,
        event["TransID"],
        "tenant-a",
    ) is ProcessResult.STORED
    store.engine.dispose()
    engine.dispose()
