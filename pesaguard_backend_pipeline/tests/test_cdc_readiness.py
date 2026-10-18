"""
CDC readiness tests (item 11).
Covers all 7 change data capture checklist items.
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

import pytest

from cdc import (
    ChangeEvent, ChangeOperation, CDCConnector, CDCOrchestrator,
    ConsistencyVerifier, OutageRecoveryManager, PostgreSQLCDCConnector,
    SchemaChangeError, SchemaChangeHandler, TableNotTrackedError,
    build_cdc_orchestrator,
)

from sqlalchemy import create_engine, MetaData, Table, Column, Integer, String, DateTime, insert, text
from datetime import datetime, timezone


@pytest.fixture
def sqlite_engine():
    engine = create_engine("sqlite:///:memory:")
    metadata = MetaData()
    test_table = Table(
        "test_transactions", metadata,
        Column("id", Integer, primary_key=True),
        Column("tenant_id", String(255), nullable=False),
        Column("amount", String(50), nullable=False),
        Column("status", String(50), nullable=False),
        Column("updated_at", DateTime(timezone=True), nullable=False),
    )
    metadata.create_all(engine)
    now = datetime.now(timezone.utc)
    with engine.begin() as conn:
        conn.execute(insert(test_table).values(
            id=1, tenant_id="tenant-a", amount="100.00", status="received",
            updated_at=now
        ))
        conn.execute(insert(test_table).values(
            id=2, tenant_id="tenant-b", amount="200.00", status="pending",
            updated_at=now
        ))
    yield engine
    engine.dispose()


@pytest.fixture
def schema_handler(sqlite_engine):
    return SchemaChangeHandler(sqlite_engine)




def test_cdc_connector_register_table(cdc_connector):
    assert cdc_connector.is_tracked("test_transactions")
    assert "test_transactions" in cdc_connector.get_tracked_tables()


def test_cdc_connector_rejects_untracked_table(cdc_connector):
    with pytest.raises(TableNotTrackedError):
        cdc_connector.capture_changes("nonexistent_table")


def test_schema_capture(cdc_connector, schema_handler):
    schema = schema_handler.get_schema("test_transactions")
    assert schema is not None
    assert schema.table_name == "test_transactions"
    assert "id" in schema.columns
    assert "tenant_id" in schema.columns
    assert "id" in schema.primary_keys


def test_debezium_config_exists():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "debezium_config.yaml.example",
    )
    assert os.path.isfile(path), "debezium_config.yaml.example must exist"
    with open(path) as f:
        content = f.read()
    assert "connector:" in content
    assert "database.hostname" in content
    assert "slot.name" in content


def test_capture_inserts(cdc_connector, sqlite_engine):
    now = datetime.now(timezone.utc)
    with sqlite_engine.begin() as conn:
        conn.execute(
            text("INSERT INTO test_transactions (id, tenant_id, amount, status, updated_at) VALUES (:id, :tid, :amt, :st, :ua)"),
            {"id": 3, "tid": "tenant-a", "amt": "300.00", "st": "received", "ua": now}
        )
    events = cdc_connector.capture_changes("test_transactions", limit=10)
    assert len(events) >= 1
    assert events[0].table_name == "test_transactions"
    assert events[0].tenant_id == "tenant-a"


def test_change_event_has_required_fields(cdc_connector):
    events = cdc_connector.capture_changes("test_transactions", limit=10)
    assert len(events) >= 1
    event = events[0]
    assert event.event_id


def test_incremental_sync_uses_checkpoint(cdc_connector, sqlite_engine):
    first_batch = cdc_connector.capture_changes("test_transactions", limit=10)
    first_count = len(first_batch)
    now = datetime.now(timezone.utc)
    with sqlite_engine.begin() as conn:
        conn.execute(
            text("INSERT INTO test_transactions (id, tenant_id, amount, status, updated_at) VALUES (:id, :tid, :amt, :st, :ua)"),
            {"id": 99, "tid": "tenant-c", "amt": "999.00", "st": "received", "ua": now}
        )
    checkpoint = cdc_connector.recovery_manager.get_checkpoint("test_transactions")
    assert checkpoint is not None
    assert checkpoint.get("events_processed") == first_count


def test_cdc_orchestrator_capture_and_publish(cdc_connector):
    published_events = []
    def publish_callback(event: ChangeEvent):
        published_events.append(event)
    orchestrator = CDCOrchestrator(cdc_connector, publish_callback)
    result = orchestrator.capture_and_publish("test_transactions", limit=10)
    assert result["events_captured"] >= 1
    assert result["events_published"] >= 1
    assert result["events_failed"] == 0
    assert len(published_events) == result["events_published"]


def test_cdc_orchestrator_capture_all_tracked(cdc_connector):
    published_events = []
    def publish_callback(event: ChangeEvent):
        published_events.append(event)
    orchestrator = CDCOrchestrator(cdc_connector, publish_callback)
    results = orchestrator.capture_all_tracked(limit=10)
    assert "test_transactions" in results
    assert results["test_transactions"]["events_captured"] >= 1


def test_schema_change_detection(sqlite_engine, schema_handler):
    schema_handler.capture_schema("test_transactions")
    with sqlite_engine.begin() as conn:
        conn.execute(text("ALTER TABLE test_transactions ADD COLUMN notes TEXT"))
    change = schema_handler.detect_changes("test_transactions")
    assert change is not None
    old_schema, new_schema = change
    assert "notes" in new_schema.columns
    assert "notes" not in old_schema.columns


def test_schema_change_handler_alert_mode(sqlite_engine, schema_handler):
    schema_handler.capture_schema("test_transactions")
    with sqlite_engine.begin() as conn:
        conn.execute(text("ALTER TABLE test_transactions ADD COLUMN notes2 TEXT"))
    schema_handler.handle_change("test_transactions", action="alert")
    assert schema_handler.get_schema("test_transactions").columns.get("notes2") == "TEXT"


def test_schema_change_handler_fail_mode(sqlite_engine, schema_handler):
    schema_handler.capture_schema("test_transactions")
    with sqlite_engine.begin() as conn:
        conn.execute(text("ALTER TABLE test_transactions ADD COLUMN notes3 TEXT"))
    with pytest.raises(SchemaChangeError):
        schema_handler.handle_change("test_transactions", action="fail")


def test_outage_recovery_checkpoint(cdc_connector, sqlite_engine):
    cdc_connector.capture_changes("test_transactions", limit=10)
    checkpoint = cdc_connector.recovery_manager.get_checkpoint("test_transactions")
    assert checkpoint is not None


def test_outage_recovery_manager_lag(cdc_connector, sqlite_engine):
    cdc_connector.capture_changes("test_transactions", limit=10)
    lag = cdc_connector.recovery_manager.get_lag_seconds("test_transactions")
    assert lag is not None
    assert lag >= 0


def test_cdc_orchestrator_retry_failed(cdc_connector):
    published = []
    def failing_publish(event: ChangeEvent):
        raise RuntimeError("Simulated publish failure")
    orchestrator = CDCOrchestrator(cdc_connector, failing_publish)
    result = orchestrator.capture_and_publish("test_transactions", limit=10)
    assert result["events_failed"] == result["events_captured"]
    def working_publish(event: ChangeEvent):
        published.append(event)
    orchestrator.publish_callback = working_publish
    retry_result = orchestrator.retry_failed()
    assert retry_result["succeeded"] == retry_result["retried"]
    assert retry_result["still_failed"] == 0


def test_consistency_verifier_consistent(sqlite_engine):
    def target_count(table: str) -> int:
        with sqlite_engine.begin() as conn:
            result = conn.execute(text(f'SELECT COUNT(*) FROM "{table}"')).fetchone()
        return result[0] if result else 0
    verifier = ConsistencyVerifier(sqlite_engine, target_count)
    result = verifier.verify_table_consistency("test_transactions")
    assert result["consistent"] is True
    assert result["source_count"] == result["target_count"]
    assert result["difference"] == 0


def test_consistency_verifier_inconsistent(sqlite_engine):
    def target_count(table: str) -> int:
        return 1
    verifier = ConsistencyVerifier(sqlite_engine, target_count)
    result = verifier.verify_table_consistency("test_transactions")
    assert result["consistent"] is False
    assert result["difference"] > 0
    assert result["reason"] is not None


def test_consistency_report_all_consistent(sqlite_engine):
    def target_count(table: str) -> int:
        with sqlite_engine.begin() as conn:
            result = conn.execute(text(f'SELECT COUNT(*) FROM "{table}"')).fetchone()
        return result[0] if result else 0
    verifier = ConsistencyVerifier(sqlite_engine, target_count)
    results = verifier.verify_all_tables(["test_transactions"])
    report = verifier.get_consistency_report(results)
    assert report["total_tables"] == 1
    assert report["consistent_tables"] == 1
    assert report["inconsistent_tables"] == 0
    assert report["all_consistent"] is True

def test_build_cdc_orchestrator():
    engine = create_engine("sqlite:///:memory:")
    from sqlalchemy import MetaData, Table, Column, Integer, String, DateTime

    metadata = MetaData()
    test_table = Table(
        "test_transactions", metadata,
        Column("id", Integer, primary_key=True),
        Column("tenant_id", String(255), nullable=False),
        Column("amount", String(50), nullable=False),
        Column("status", String(50), nullable=False),
        Column("updated_at", DateTime(timezone=True), nullable=False),
    )
    metadata.create_all(engine)

    orchestrator = build_cdc_orchestrator(
        "sqlite:///:memory:",
        tracked_tables=["test_transactions"],
        engine=engine,
    )
    assert orchestrator is not None


@pytest.fixture
def recovery_manager(sqlite_engine):
    return OutageRecoveryManager(sqlite_engine)


@pytest.fixture
def cdc_connector(sqlite_engine, schema_handler, recovery_manager):
    connector = PostgreSQLCDCConnector(
        sqlite_engine, schema_handler, recovery_manager,
        source_name="test-postgres"
    )
    connector.register_table("test_transactions")
    return connector


def test_change_event_checksum_is_deterministic(cdc_connector):
    events = cdc_connector.capture_changes("test_transactions", limit=10)
    assert len(events) >= 1
    event = events[0]
    assert event.compute_checksum() == event.compute_checksum()


# --- TombstoneDetector tests ---

def test_tombstone_detector_records_and_detects_deletes():
    from cdc import TombstoneDetector

    detector = TombstoneDetector()
    table = "orders"

    # First cycle: see keys 1, 2, 3
    detector.update_seen(table, {"key-1", "key-2", "key-3"})
    detector.record_seen(table, "key-1", {"id": "key-1", "tenant_id": "t1"})
    detector.record_seen(table, "key-2", {"id": "key-2", "tenant_id": "t2"})
    detector.record_seen(table, "key-3", {"id": "key-3", "tenant_id": "t3"})

    # Second cycle: key-2 disappeared -> delete detected
    deleted = detector.detect_deletes(table, {"key-1", "key-3"})
    assert "key-2" in deleted
    assert "key-1" not in deleted
    assert "key-3" not in deleted

    last_values = detector.get_last_values(table, "key-2")
    assert last_values is not None
    assert last_values["tenant_id"] == "t2"


def test_tombstone_detector_no_deletes_when_keys_stable():
    from cdc import TombstoneDetector

    detector = TombstoneDetector()
    table = "orders"

    detector.update_seen(table, {"key-1", "key-2"})
    detector.record_seen(table, "key-1", {"id": "key-1"})
    detector.record_seen(table, "key-2", {"id": "key-2"})

    deleted = detector.detect_deletes(table, {"key-1", "key-2"})
    assert deleted == []


def test_tombstone_detector_new_keys_not_deletes():
    from cdc import TombstoneDetector

    detector = TombstoneDetector()
    table = "orders"

    detector.update_seen(table, {"key-1"})
    detector.record_seen(table, "key-1", {"id": "key-1"})

    deleted = detector.detect_deletes(table, {"key-1", "key-2"})
    assert deleted == []


def test_tombstone_detector_reset():
    from cdc import TombstoneDetector

    detector = TombstoneDetector()
    table = "orders"

    detector.update_seen(table, {"key-1", "key-2"})
    detector.record_seen(table, "key-1", {"id": "key-1"})
    detector.record_seen(table, "key-2", {"id": "key-2"})

    detector.reset(table)
    deleted = detector.detect_deletes(table, set())
    assert deleted == []


# --- PostgreSQLLogicalDecodingConnector tests ---

def test_logical_decoding_connector_instantiation():
    from cdc import PostgreSQLLogicalDecodingConnector

    conn = PostgreSQLLogicalDecodingConnector(
        source_name="test-wal",
        connection_string="postgresql://localhost:5432/testdb",
        slot_name="test_slot",
    )
    assert conn.source_name == "test-wal"
    assert conn.slot_name == "test_slot"
    assert conn.plugin == "pg_output"
    assert conn._tracked_tables == set()


def test_logical_decoding_connector_graceful_fallback(monkeypatch):
    from cdc import PostgreSQLLogicalDecodingConnector, ChangeOperation

    conn = PostgreSQLLogicalDecodingConnector(
        source_name="test-wal",
        connection_string="postgresql://localhost:5432/testdb",
    )

    # Simulate replication privileges unavailable
    monkeypatch.setattr(conn, "_ensure_replication_available", lambda: False)

    events = conn.capture_changes("test_transactions")
    assert events == []


def test_logical_decoding_connector_track_table():
    from cdc import PostgreSQLLogicalDecodingConnector

    conn = PostgreSQLLogicalDecodingConnector(
        source_name="test-wal",
        connection_string="postgresql://localhost:5432/testdb",
    )

    conn.track_table("test_transactions")
    assert conn.is_tracked("test_transactions")

    # Calling track_table again should be idempotent
    conn.track_table("test_transactions")
    assert conn.is_tracked("test_transactions")
