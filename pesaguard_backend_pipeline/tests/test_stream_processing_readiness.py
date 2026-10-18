"""
Phase 7 — Stream Processing: Production Readiness Certification.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest

from event_bus import EventEnvelope, build_event
from stream_processing import (
    AnomalyIndicator,
    CheckpointStore,
    ContinuousAnomalyMonitor,
    DuplicateDetector,
    EnrichedTransaction,
    LateEventCorrector,
    LateEventCorrection,
    ReplaySafeProcessor,
    StreamingReconciliation,
    WindowAggregator,
    compute_event_fingerprint,
    compute_state_hash,
    enrich_stream_event,
    check_stream_batch_consistency,
)
from enrichment_service import EnrichmentService


def _sample_event() -> EventEnvelope:
    return build_event(
        "transaction.received",
        "tenant-a",
        "agg-1",
        {
            "TransID": "tx-1",
            "TransAmount": "100.00",
            "Currency": "KES",
            "provider": "mpesa",
            "provider_account_id": "123456",
            "phone_number": "254700000001",
            "TransactionType": "PAYMENT",
            "Status": "RECEIVED",
        },
        source="mpesa",
        source_event_id="provider-event-1",
    )


def test_enrichment_produces_enriched_transaction():
    service = EnrichmentService()
    event = _sample_event()
    result = enrich_stream_event(event, enrichment_service=service)
    assert isinstance(result, EnrichedTransaction)
    assert result.event_id == event.event_id
    assert result.tenant_id == "tenant-a"
    assert result.canonical.transaction_id == "tx-1"
    assert result.canonical.amount == "100.00"
    assert result.canonical.currency == "KES"
    assert "merchant" in result.enrichment
    assert "customer" in result.enrichment
    assert "historical" in result.enrichment
    assert "risk_profile" in result.enrichment
    assert "device" in result.enrichment


def test_enrichment_is_deterministic():
    service = EnrichmentService()
    event = _sample_event()
    r1 = enrich_stream_event(event, enrichment_service=service)
    r2 = enrich_stream_event(event, enrichment_service=service)
    assert r1.enrichment == r2.enrichment
    # Risk decision may have different detected_at, compare other fields
    assert r1.risk_decision["risk_score"] == r2.risk_decision["risk_score"]
    assert r1.risk_decision["risk_level"] == r2.risk_decision["risk_level"]
    assert r1.risk_decision["action"] == r2.risk_decision["action"]


def test_streaming_reconciliation_matches():
    recon = StreamingReconciliation(window_seconds=900)
    event = build_event(
        "transaction.received", "tenant-a", "agg-1",
        {"TransID": "tx-1", "TransAmount": "100.00", "Currency": "KES",
         "provider": "mpesa", "provider_account_id": "123456",
         "TransTime": "20260101120000", "Status": "RECEIVED"},
        source="mpesa",
    )
    internal = [{
        "transaction_id": "tx-1", "tenant_id": "tenant-a",
        "amount": "100.00", "currency": "KES",
        "transaction_time": "20260101120000", "timestamp": "20260101120000", "status": "RECEIVED",
        "internal_ref": "tx-1", "reference": "tx-1",
    }]
    result = recon.reconcile_stream_event(event, internal_records=internal)
    assert result.status == "MATCHED"
    assert result.transaction_id == "tx-1"


def test_streaming_reconciliation_duplicate_suppression():
    recon = StreamingReconciliation(window_seconds=900)
    event = build_event(
        "transaction.received", "tenant-a", "agg-1",
        {"TransID": "tx-1", "TransAmount": "100.00", "Currency": "KES",
         "provider": "mpesa", "provider_account_id": "123456",
         "TransTime": "20260101120000", "Status": "RECEIVED"},
        source="mpesa",
    )
    internal = [{
        "transaction_id": "tx-1", "tenant_id": "tenant-a",
        "amount": "100.00", "currency": "KES",
        "transaction_time": "20260101120000", "timestamp": "20260101120000", "status": "RECEIVED",
        "internal_ref": "tx-1", "reference": "tx-1",
    }]
    r1 = recon.reconcile_stream_event(event, internal_records=internal)
    r2 = recon.reconcile_stream_event(event, internal_records=internal)
    assert r1 is r2



def test_window_aggregator_tumbling_buckets():
    agg = WindowAggregator(window_seconds=300)
    base = datetime(2026, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    agg.add_event("tenant-a", base.isoformat(), amount="100.00", currency="KES", status="matched", risk_score=0.1)
    agg.add_event("tenant-a", (base + timedelta(seconds=60)).isoformat(), amount="200.00", currency="KES", status="unmatched")
    agg.add_event("tenant-b", base.isoformat(), amount="50.00", currency="KES", status="exception", risk_score=0.9)
    snap = agg.snapshot()
    assert snap["active_windows"] == 2
    tenant_a = [w for w in snap["windows"] if w["tenant_id"] == "tenant-a"][0]
    assert tenant_a["event_count"] == 2
    assert tenant_a["matched_count"] == 1
    assert tenant_a["unmatched_count"] == 1


def test_window_aggregator_complete_windows_before():
    agg = WindowAggregator(window_seconds=300)
    base = datetime(2026, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    agg.add_event("tenant-a", base.isoformat(), amount="100.00")
    agg.add_event("tenant-a", (base + timedelta(seconds=290)).isoformat(), amount="50.00")
    completed = agg.complete_windows_before("2026-01-01T12:05:00+00:00")
    assert len(completed) == 1
    assert completed[0].event_count == 2
    assert agg.snapshot()["active_windows"] == 0



def test_duplicate_detector_catches_duplicates():
    det = DuplicateDetector(ttl_seconds=3600)
    is_dup, orig = det.check_and_record("tenant-a", "evt-1", "fp-1")
    assert is_dup is False
    assert orig is None
    is_dup2, orig2 = det.check_and_record("tenant-a", "evt-2", "fp-1")
    assert is_dup2 is True
    assert orig2 == "evt-1"


def test_duplicate_detector_ttl_expiry():
    det = DuplicateDetector(ttl_seconds=1)
    det.check_and_record("tenant-a", "evt-1", "fp-1")
    now = datetime.now(timezone.utc) + timedelta(seconds=2)
    is_dup, _ = det.check_and_record("tenant-a", "evt-2", "fp-1", now=now)
    assert is_dup is False


def test_compute_event_fingerprint_is_stable():
    e1 = _sample_event()
    e2 = _sample_event()
    assert compute_event_fingerprint(e1) == compute_event_fingerprint(e2)
    e3 = build_event(
        "transaction.received", "tenant-a", "agg-2",
        {"TransID": "tx-2", "TransAmount": "100.00", "Currency": "KES",
         "provider": "mpesa", "provider_account_id": "123456"},
        source="mpesa",
    )
    assert compute_event_fingerprint(e1) != compute_event_fingerprint(e3)



def test_anomaly_monitor_velocity_breach():
    mon = ContinuousAnomalyMonitor(window_seconds=300, velocity_threshold=10.0)
    base = datetime(2026, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    for i in range(15):
        mon.observe("tenant-a", (base + timedelta(seconds=i * 10)).isoformat(), amount="100.00", now=base)
    inds = mon.observe("tenant-a", (base + timedelta(seconds=150)).isoformat(), amount="100.00", now=base + timedelta(seconds=150))
    velocity_breach = [i for i in inds if i.indicator_type == "velocity"]
    assert len(velocity_breach) == 1
    assert velocity_breach[0].breach is True


def test_anomaly_monitor_amount_deviation():
    mon = ContinuousAnomalyMonitor(window_seconds=300, amount_deviation_threshold=2.0)
    base = datetime(2026, 1, 1, 12, 0, 0, tzinfo=timezone.utc)
    for i in range(10):
        mon.observe("tenant-a", (base + timedelta(seconds=i * 10)).isoformat(), amount="100.00", now=base)
    inds = mon.observe("tenant-a", (base + timedelta(seconds=100)).isoformat(), amount="10000.00", now=base + timedelta(seconds=100))
    dev_breach = [i for i in inds if i.indicator_type == "amount_deviation"]
    assert len(dev_breach) == 1
    assert dev_breach[0].breach is True



def test_late_event_corrector_registers():
    corr = LateEventCorrector()
    c = corr.register_correction(
        tenant_id="tenant-a",
        aggregate_id="agg-1",
        original_event_id="evt-1",
        corrected_event_id="evt-2",
        correction_type="amount",
        original_values={"amount": "100.00"},
        corrected_values={"amount": "200.00"},
        reason="Amount was transposed",
    )
    assert c.correction_type == "amount"
    assert c.original_values["amount"] == "100.00"
    assert c.corrected_values["amount"] == "200.00"
    assert corr.is_corrected("evt-1") is True


def test_late_event_corrector_retrieval():
    corr = LateEventCorrector()
    corr.register_correction(
        tenant_id="tenant-a", aggregate_id="agg-1",
        original_event_id="evt-1", corrected_event_id="evt-2",
        correction_type="status",
        original_values={"status": "RECEIVED"},
        corrected_values={"status": "REVERSED"},
        reason="Duplicate payment reversed",
    )
    corrections = corr.get_corrections_for_aggregate("agg-1")
    assert len(corrections) == 1
    assert corrections[0].correction_type == "status"



def test_checkpoint_store_save_and_load():
    store = CheckpointStore()
    cp = store.save(
        tenant_id="tenant-a",
        stage="enrichment",
        last_event_id="evt-100",
        last_event_time="2026-01-01T12:00:00+00:00",
        offset=100,
        state_hash="abc123",
    )
    assert cp.tenant_id == "tenant-a"
    assert cp.stage == "enrichment"
    assert cp.last_event_id == "evt-100"
    assert cp.offset == 100
    loaded = store.load("tenant-a", "enrichment")
    assert loaded is not None
    assert loaded.state_hash == "abc123"


def test_compute_state_hash_is_deterministic():
    h1 = compute_state_hash({"a": 1, "b": 2})
    h2 = compute_state_hash({"b": 2, "a": 1})
    assert h1 == h2
    h3 = compute_state_hash({"a": 1, "b": 3})
    assert h1 != h3



def test_replay_safe_processor_duplicate_suppression():
    service = EnrichmentService()
    proc = ReplaySafeProcessor(enrichment_service=service)
    event = _sample_event()
    internal = [{
        "transaction_id": "tx-1", "tenant_id": "tenant-a",
        "amount": "100.00", "currency": "KES",
        "transaction_time": "20260101120000", "timestamp": "20260101120000", "status": "RECEIVED",
        "internal_ref": "tx-1", "reference": "tx-1",
    }]
    r1 = proc.process_event(event, internal_records=internal)
    assert r1["status"] == "processed"
    assert r1["duplicate"] is False
    r2 = proc.process_event(event, internal_records=internal)
    assert r2["status"] == "duplicate"
    assert r2["duplicate"] is True
    assert r2["original_event_id"] == event.event_id


def test_replay_safe_processor_metrics():
    service = EnrichmentService()
    proc = ReplaySafeProcessor(enrichment_service=service)
    event = _sample_event()
    internal = [{
        "transaction_id": "tx-1", "tenant_id": "tenant-a",
        "amount": "100.00", "currency": "KES",
        "transaction_time": "20260101120000", "timestamp": "20260101120000", "status": "RECEIVED",
        "internal_ref": "tx-1", "reference": "tx-1",
    }]
    proc.process_event(event, internal_records=internal)
    proc.process_event(event, internal_records=internal)
    m = proc.metrics()
    assert m["processed_count"] == 1
    assert m["duplicate_count"] == 1
    assert m["duplicate_rate"] == 0.5


def test_checkpoint_and_recovery():
    service = EnrichmentService()
    store = CheckpointStore()
    proc = ReplaySafeProcessor(enrichment_service=service, checkpoint_store=store)
    event = _sample_event()
    internal = [{
        "transaction_id": "tx-1", "tenant_id": "tenant-a",
        "amount": "100.00", "currency": "KES",
        "transaction_time": "20260101120000", "timestamp": "20260101120000", "status": "RECEIVED",
        "internal_ref": "tx-1", "reference": "tx-1",
    }]
    proc.process_event(event, internal_records=internal)
    cp = proc.checkpoint("tenant-a", "enrichment", offset=1)
    assert cp.offset == 1
    assert cp.state_hash is not None
    loaded = store.load("tenant-a", "enrichment")
    assert loaded is not None
    assert loaded.state_hash == cp.state_hash



def test_consistency_check_matching():
    stream = {
        "processed_count": 100,
        "duplicate_count": 5,
        "window_aggregator": {
            "windows": [
                {"tenant_id": "tenant-a", "window_start": "2026-01-01T12:00:00+00:00",
                 "event_count": 50, "total_amount": "5000.00"},
            ]
        },
    }
    batch = {
        "processed_count": 100,
        "duplicate_count": 5,
        "window_aggregator": {
            "windows": [
                {"tenant_id": "tenant-a", "window_start": "2026-01-01T12:00:00+00:00",
                 "event_count": 50, "total_amount": "5000.00"},
            ]
        },
    }
    result = check_stream_batch_consistency(stream_metrics=stream, batch_metrics=batch)
    assert result["consistent"] is True
    assert result["divergence_count"] == 0


def test_consistency_check_divergence():
    stream = {
        "processed_count": 100,
        "duplicate_count": 5,
        "window_aggregator": {
            "windows": [
                {"tenant_id": "tenant-a", "window_start": "2026-01-01T12:00:00+00:00",
                 "event_count": 50, "total_amount": "5000.00"},
            ]
        },
    }
    batch = {
        "processed_count": 105,
        "duplicate_count": 5,
        "window_aggregator": {
            "windows": [
                {"tenant_id": "tenant-a", "window_start": "2026-01-01T12:00:00+00:00",
                 "event_count": 50, "total_amount": "5000.00"},
            ]
        },
    }
    result = check_stream_batch_consistency(stream_metrics=stream, batch_metrics=batch, tolerance_percent=1.0)
    assert result["consistent"] is False
    assert result["divergence_count"] >= 1
