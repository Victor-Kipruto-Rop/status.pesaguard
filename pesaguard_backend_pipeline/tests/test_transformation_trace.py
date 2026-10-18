# type: ignore-file-at-eof
"""Tests for raw-to-reconciliation traceability (provenance without payloads)."""

import os
import uuid
from datetime import datetime, timezone

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from lineage import record_lineage
from models import (
    Base,
    DeadLetter,
    Discrepancy,
    ReconciliationMatch,
    Transaction,
    TransactionEvent,
    TransformationRecord,
)
from transformation_store import record_transformation_stage
from transformation_trace import TraceError, trace_transaction
from transformation_trace import TraceError, trace_transaction


@pytest.fixture()
def session_factory():
    url = os.environ["DATABASE_URL"]
    engine = create_engine(
        url, connect_args={"check_same_thread": False} if url.startswith("sqlite") else {}
    )
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    yield factory
    engine.dispose()


def _trans_id() -> str:
    return f"TRACE-{uuid.uuid4().hex[:10].upper()}"


def _seed_trail(session_factory, tenant: str, trans_id: str) -> None:
    with session_factory() as session:
        record_transformation_stage(
            session,
            tenant_id=tenant,
            transaction_id=trans_id,
            event_id=f"evt-{trans_id}",
            stage="RAW",
            payload={"trans_id": trans_id, "msisdn": "254700000000"},
        )
        record_transformation_stage(
            session,
            tenant_id=tenant,
            transaction_id=trans_id,
            event_id=f"evt-{trans_id}",
            stage="NORMALIZED",
            payload={"trans_id": trans_id},
            source_event_id=f"evt-{trans_id}",
        )
        record_lineage(
            session,
            tenant_id=tenant,
            transaction_id=trans_id,
            event_id=f"lin-{trans_id}",
            stage="INGESTED",
            source="mpesa",
        )
        session.add(
            Transaction(
                id=f"txn-trace-{trans_id}",
                tenant_id=tenant,
                trans_id=trans_id,
                trans_amount=10.5,
                provider="mpesa",
                currency="KES",
                msisdn="254700000001",
                business_short_code="123456",
                trans_time="20260923120000",
                raw_payload={"trans_id": trans_id},
                status="RECONCILED",
            )
        )
        session.add(
            TransactionEvent(
                id=f"ev-trace-{trans_id}",
                tenant_id=tenant,
                transaction_id=f"txn-trace-{trans_id}",
                trans_id=trans_id,
                event_key=f"evkey-{trans_id}",
                event_type="ingested",
                to_state="INGESTED",
                actor="trace-seed",
                details={},
            )
        )
        session.add(
            ReconciliationMatch(
                id=f"match-{trans_id}",
                tenant_id=tenant,
                transaction_id=trans_id,
                match_score=0.99,
                status="MATCHED",
                engine_version="v1",
                processing_latency_ms=5.0,
                match_timestamp=datetime.now(timezone.utc),
            )
        )
        session.add(
            Discrepancy(
                id=f"{trans_id}-amount",
                tenant_id=tenant,
                trans_id=trans_id,
                anomaly_type="amount_mismatch",
                status="resolved",
                severity="warning",
            )
        )
        session.add(
            DeadLetter(
                id=f"dl-trace-{trans_id}",
                tenant_id=tenant,
                reason="import_late",
                payload={"late_ref": trans_id},
                event_key=f"trace-{trans_id}",
            )
        )
        session.commit()


def test_trace_assembles_full_trail_without_payload_values(session_factory):
    tenant = f"tenant-{uuid.uuid4().hex[:8]}"
    trans_id = _trans_id()
    _seed_trail(session_factory, tenant, trans_id)
    with session_factory() as session:
        trace = trace_transaction(session, tenant_id=tenant, transaction_id=trans_id)
    assert trace.tenant_id == tenant
    assert [stage.stage for stage in trace.stages] == ["RAW", "NORMALIZED"]
    assert all("254700000000" not in stage.payload_hash for stage in trace.stages)
    assert [hop.stage for hop in trace.lineage] == ["INGESTED"]
    assert trace.transaction_status == "RECONCILED"
    assert trace.reconciliation_status == "MATCHED"
    assert trace.match_score == pytest.approx(0.99)
    assert [item.anomaly_type for item in trace.discrepancies] == ["amount_mismatch"]
    assert [item.reason for item in trace.dead_letters] == ["import_late"]
    assert trace.event_count == 1
    payload = trace.to_payload()
    assert "254700000000" not in str(payload)


def test_trace_is_tenant_scoped(session_factory):
    tenant = f"tenant-{uuid.uuid4().hex[:8]}"
    trans_id = _trans_id()
    _seed_trail(session_factory, tenant, trans_id)
    other = f"tenant-{uuid.uuid4().hex[:8]}"
    with session_factory() as session:
        trace = trace_transaction(session, tenant_id=other, transaction_id=trans_id)
    assert trace.stages == [] and trace.lineage == []
    assert trace.reconciliation_status is None and trace.event_count == 0


def test_trace_by_internal_guid_resolves_to_trans_id(session_factory):
    tenant = f"tenant-{uuid.uuid4().hex[:8]}"
    trans_id = _trans_id()
    _seed_trail(session_factory, tenant, trans_id)
    with session_factory() as session:
        trace = trace_transaction(
            session, tenant_id=tenant, transaction_id=f"txn-trace-{trans_id}"
        )
    assert trace.reconciliation_status == "MATCHED"


def test_trace_rejects_blank_contexts(session_factory):
    with session_factory() as session:
        with pytest.raises(TraceError, match="tenant_id is required"):
            trace_transaction(session, tenant_id="", transaction_id="x")
        with pytest.raises(TraceError, match="transaction_id is required"):
            trace_transaction(session, tenant_id="tenant-a", transaction_id="")