from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from types import SimpleNamespace

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from action_audit import ActionAuditEntry
from event_store import EventStore, ProcessResult
from models import (
    Base,
    Discrepancy,
    FraudRiskAssessment,
    ProcessedTransaction,
    ReconciliationMatch,
    ReconciliationOutbox,
)
import reconciliation_job
from reconciliation_job import _persist_atomically


def _event():
    return {
        "TransID": "TX-STATE-1",
        "TransAmount": "10.00",
        "MSISDN": "254700000000",
        "BusinessShortCode": "123456",
        "TransTime": "20260912120000",
    }


def test_reconciliation_is_separate_from_ingestion_and_replay_safe(tmp_path, monkeypatch):
    database_url = f"sqlite:///{tmp_path / 'reconciliation.db'}"
    engine = create_engine(database_url)

    import reconciliation_job
    from action_audit import Base as AuditBase

    Base.metadata.create_all(engine)
    AuditBase.metadata.create_all(engine)
    monkeypatch.setattr(reconciliation_job, "AuditSession", sessionmaker(bind=engine, expire_on_commit=False))
    store = EventStore(database_url=database_url)
    event = _event()
    assert store.mark_processed(event, tenant_id="tenant-a") is ProcessResult.STORED

    evaluation = {
        "status": "missing_payment",
        "severity": "critical",
        "phase3_status": "UNMATCHED",
        "match": {"match_type": "none"},
        "anomalies": ["missing_payment"],
        "event": event,
        "tenant_id": "tenant-a",
        "evidence": {
            "matched_record": None,
            "matching_rules": ["missing_internal_record"],
            "match_score": 0.0,
            "match_timestamp": "2026-09-12T12:00:00+00:00",
            "engine_version": "phase3.3",
            "rule_version": "phase3.3",
        },
    }
    assert _persist_atomically(event, evaluation, event["TransID"], "tenant-a") is ProcessResult.STORED
    assert _persist_atomically(event, evaluation, event["TransID"], "tenant-a") is ProcessResult.DUPLICATE

    with sessionmaker(bind=engine)() as session:
        assert session.query(ProcessedTransaction).one().reconciliation_status == "completed"
        assert session.query(Discrepancy).count() == 1
        assert session.query(ActionAuditEntry).count() == 1
        assert session.query(ReconciliationOutbox).count() == 1
        assessment = session.query(FraudRiskAssessment).one()
        assert "duplicate_activity" not in assessment.rules_triggered


def test_concurrent_reconciliation_attempts_write_one_durable_decision(tmp_path, monkeypatch):
    database_url = f"sqlite:///{tmp_path / 'reconciliation-concurrent.db'}"
    engine = create_engine(database_url, connect_args={"timeout": 10})
    from action_audit import Base as AuditBase

    Base.metadata.create_all(engine)
    AuditBase.metadata.create_all(engine)
    monkeypatch.setattr(
        reconciliation_job,
        "AuditSession",
        sessionmaker(bind=engine, expire_on_commit=False),
    )
    event = _event()
    assert EventStore(database_url=database_url).mark_processed(event, tenant_id="tenant-a") is ProcessResult.STORED
    evaluation = {
        "trans_id": event["TransID"],
        "tenant_id": "tenant-a",
        "status": "matched",
        "phase3_status": "MATCHED",
        "severity": "info",
        "match": {"match_type": "matched"},
        "anomalies": [],
        "event": event,
        "evidence": {
            "matched_record": {"internal_ref": "ledger-1"},
            "matching_rules": ["reference_exact", "amount_exact"],
            "match_score": 1.0,
            "match_timestamp": "2026-09-12T12:00:00+00:00",
            "engine_version": "phase3.3",
            "rule_version": "phase3.3",
        },
    }
    barrier = Barrier(2)

    def reconcile():
        barrier.wait()
        return _persist_atomically(event, evaluation, event["TransID"], "tenant-a")

    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(lambda _: reconcile(), range(2)))

    assert outcomes.count(ProcessResult.STORED) == 1
    assert all(outcome in {ProcessResult.STORED, ProcessResult.DUPLICATE, ProcessResult.ERROR} for outcome in outcomes)
    with sessionmaker(bind=engine)() as session:
        assert session.query(ProcessedTransaction).one().reconciliation_status == "completed"
        assert session.query(ActionAuditEntry).count() == 1
        assert session.query(ReconciliationOutbox).count() == 1
        assert session.query(ReconciliationMatch).count() == 1
    engine.dispose()


def test_failed_audit_write_rolls_back_the_entire_reconciliation(tmp_path, monkeypatch):
    database_url = f"sqlite:///{tmp_path / 'reconciliation-rollback.db'}"
    engine = create_engine(database_url)
    from action_audit import Base as AuditBase

    Base.metadata.create_all(engine)
    AuditBase.metadata.create_all(engine)
    monkeypatch.setattr(
        reconciliation_job,
        "AuditSession",
        sessionmaker(bind=engine, expire_on_commit=False),
    )
    event = _event()
    assert EventStore(database_url=database_url).mark_processed(event, tenant_id="tenant-a") is ProcessResult.STORED
    def fail_audit(*args, **kwargs):
        raise RuntimeError("audit database unavailable")

    monkeypatch.setattr(reconciliation_job, "persist_audit_event", fail_audit)
    evaluation = {
        "status": "matched",
        "phase3_status": "MATCHED",
        "severity": "info",
        "match": {"match_type": "exact"},
        "anomalies": [],
        "event": event,
        "tenant_id": "tenant-a",
        "evidence": {
            "matched_record": {"internal_ref": "ledger-1"},
            "matching_rules": ["reference_exact", "amount_exact"],
            "match_score": 1.0,
            "match_timestamp": "2026-09-12T12:00:00+00:00",
            "engine_version": "phase3.3",
            "rule_version": "phase3.3",
        },
    }

    assert _persist_atomically(event, evaluation, event["TransID"], "tenant-a") is ProcessResult.ERROR
    with sessionmaker(bind=engine)() as session:
        assert session.query(ProcessedTransaction).one().reconciliation_status == "pending"
        assert session.query(FraudRiskAssessment).count() == 0
        assert session.query(Discrepancy).count() == 0
        assert session.query(ReconciliationMatch).count() == 0
        assert session.query(ReconciliationOutbox).count() == 0
        assert session.query(ActionAuditEntry).count() == 0
    engine.dispose()


def test_commit_crash_after_durable_reconciliation_replay_has_no_duplicate_effects(
    tmp_path, monkeypatch
):
    database_url = f"sqlite:///{tmp_path / 'reconciliation-commit-crash.db'}"
    engine = create_engine(database_url)
    from action_audit import Base as AuditBase

    Base.metadata.create_all(engine)
    AuditBase.metadata.create_all(engine)
    monkeypatch.setattr(
        reconciliation_job,
        "AuditSession",
        sessionmaker(bind=engine, expire_on_commit=False),
    )
    event = {**_event(), "tenant_id": "tenant-a"}
    evaluation = {
        "trans_id": event["TransID"],
        "tenant_id": "tenant-a",
        "status": "matched",
        "phase3_status": "MATCHED",
        "severity": "info",
        "match": {"match_type": "matched"},
        "anomalies": [],
        "evidence": {
            "matched_record": None,
            "matching_rules": ["reference_exact", "amount_exact"],
            "match_score": 1.0,
            "match_timestamp": "2026-09-12T12:00:00+00:00",
            "engine_version": "phase3.3",
            "rule_version": "phase3.3",
        },
        "event": event,
    }
    persisted_outcomes = []

    def process(received_event, *args):
        outcome = _persist_atomically(
            received_event,
            evaluation,
            received_event["TransID"],
            "tenant-a",
        )
        persisted_outcomes.append(outcome)
        return outcome in {ProcessResult.STORED, ProcessResult.DUPLICATE}

    commit_attempts = []

    def commit(_consumer, _message):
        commit_attempts.append(True)
        if len(commit_attempts) == 1:
            raise RuntimeError("commit outcome unknown")

    monkeypatch.setattr(reconciliation_job, "_process_message", process)
    monkeypatch.setattr(reconciliation_job, "_commit_message", commit)
    message = SimpleNamespace(
        value=event,
        topic="mpesa.transactions.raw",
        partition=0,
        offset=11,
    )

    with pytest.raises(reconciliation_job._MessageRetryRequired):
        reconciliation_job._handle_message(None, message, None, None)
    assert reconciliation_job._handle_message(None, message, None, None) is True
    assert persisted_outcomes == [ProcessResult.STORED, ProcessResult.DUPLICATE]
    assert len(commit_attempts) == 2

    with sessionmaker(bind=engine)() as session:
        assert session.query(ProcessedTransaction).count() == 1
        assert session.query(ActionAuditEntry).count() == 1
        assert session.query(ReconciliationOutbox).count() == 1
        assert session.query(FraudRiskAssessment).count() == 1
    engine.dispose()


def test_reconciliation_uses_tenant_anomaly_settings(monkeypatch):
    import reconciliation_job

    captured = {}
    tenant_settings = {"anomaly_large_amount_kes": 250}
    monkeypatch.setattr(reconciliation_job.settings_store, "get", lambda tenant_id: tenant_settings)
    monkeypatch.setattr(
        reconciliation_job,
        "check_for_anomalies",
        lambda event, seen, settings: captured.update(settings=settings) or [],
    )
    monkeypatch.setattr(
        reconciliation_job,
        "reconciliation_engine",
        type(
            "FakeEngine",
            (),
            {
                "reconcile": staticmethod(
                    lambda event, records, seen_transaction_ids: {
                        "status": "MATCHED",
                        "evidence": {
                            "matched_record": None,
                            "matching_rules": ["reference_exact"],
                            "match_score": 1.0,
                            "match_timestamp": "2026-09-12T12:00:00+00:00",
                            "engine_version": "test",
                        },
                        "processing_latency_ms": 0,
                    }
                )
            },
        )(),
    )
    monkeypatch.setattr(reconciliation_job, "_persist_atomically", lambda *args: ProcessResult.STORED)
    monkeypatch.setattr(reconciliation_job, "_publish_downstream", lambda *args: None)
    registry = type("Registry", (), {"get_connector": lambda self, tenant_id: None})()

    assert reconciliation_job._process_message_unbounded(
        {**_event(), "tenant_id": "tenant-a"},
        None,
        None,
        registry,
    )
    assert captured["settings"] == tenant_settings


def test_high_risk_match_is_persisted_and_published_as_a_discrepancy(tmp_path, monkeypatch):
    import json
    from types import SimpleNamespace

    import reconciliation_job
    from action_audit import Base as AuditBase

    database_url = f"sqlite:///{tmp_path / 'high-risk.db'}"
    engine = create_engine(database_url)
    Base.metadata.create_all(engine)
    AuditBase.metadata.create_all(engine)
    monkeypatch.setattr(reconciliation_job, "AuditSession", sessionmaker(bind=engine, expire_on_commit=False))
    monkeypatch.setattr(
        reconciliation_job,
        "assess_transaction",
        lambda event, history: SimpleNamespace(
            risk_level="CRITICAL",
            reason_codes=("amount_threshold",),
            as_dict=lambda: {"risk_level": "CRITICAL", "reason_codes": ["amount_threshold"]},
        ),
    )
    monkeypatch.setattr(reconciliation_job, "persist_assessment", lambda *args: None)

    event = _event()
    assert EventStore(database_url=database_url).mark_processed(event, tenant_id="tenant-a") is ProcessResult.STORED
    evaluation = {
        "trans_id": event["TransID"],
        "tenant_id": "tenant-a",
        "status": "matched",
        "phase3_status": "MATCHED",
        "severity": "info",
        "anomalies": [],
        "evidence": {
            "match_timestamp": "2026-09-12T12:00:00+00:00",
            "matching_rules": ["reference_exact"],
            "match_score": 1.0,
            "engine_version": "phase3.3",
            "rule_version": "phase3.3",
        },
        "event": event,
    }
    assert reconciliation_job._persist_atomically(event, evaluation, event["TransID"], "tenant-a") is ProcessResult.STORED

    with sessionmaker(bind=engine)() as session:
        discrepancy = session.query(Discrepancy).one()
        outbox = session.query(ReconciliationOutbox).one()
        assert discrepancy.anomaly_type == "fraud_risk"
        assert discrepancy.severity == "critical"
        assert outbox.topic == reconciliation_job.TOPIC_DISCREPANCIES
        assert outbox.payload["fraud_risk"]["risk_level"] == "CRITICAL"

    published = []
    alerts = []

    class Future:
        def get(self, timeout=None):
            return None

    class Producer:
        def send(self, topic, key=None, value=None):
            published.append((topic, json.loads(value)))
            return Future()

        def flush(self, timeout=None):
            return None

    monkeypatch.setattr(
        reconciliation_job,
        "dispatch_discrepancy_alert",
        lambda payload, tenant_id: alerts.append((payload, tenant_id)) or {"status": "queued"},
    )
    reconciliation_job._publish_downstream(evaluation, event["TransID"], Producer(), "tenant-a")

    assert published[0][0] == reconciliation_job.TOPIC_DISCREPANCIES
    assert published[0][1]["fraud_risk"]["risk_level"] == "CRITICAL"
    assert alerts[0][0]["severity"] == "critical"
    assert alerts[0][0]["anomalies"] == ["amount_threshold"]
    engine.dispose()
