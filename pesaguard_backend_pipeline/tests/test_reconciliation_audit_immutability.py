"""Service 50 — Reconciliation Audit Service: durable + tamper-resistant trail.

Rules #34/#35 require the reconciliation audit trail to be traceable and
immutable. ``_persist_atomically`` records an ``ActionAuditEntry`` for every
reconciliation outcome inside the same transaction as the ledger and match
rows. These tests close the defence-in-depth gap that the reconciliation phase
had no coverage for: once a reconciliation decision is audited, the audit row
must be durable AND immutable at the database boundary — a direct SQL UPDATE or
DELETE that bypasses the ORM guards must still be rejected by the append-only
trigger (Rule #23).

Behaviour verified against the current engine before being written here.
"""

from __future__ import annotations

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from action_audit import ActionAuditEntry, Base as AuditBase
from event_store import EventStore, ProcessResult
from models import Base, ProcessedTransaction, ReconciliationMatch
from reconciliation_job import _persist_atomically


def _event():
    return {
        "TransID": "TX-AUDIT-1",
        "TransAmount": "10.00",
        "MSISDN": "254700000000",
        "BusinessShortCode": "123456",
        "TransTime": "20260912120000",
    }


def _seed_audited_reconciliation(tmp_path, monkeypatch, *, tenant_id="tenant-a"):
    """Persist one reconciliation outcome and return (engine, trans_id)."""
    import reconciliation_job

    database_url = f"sqlite:///{tmp_path / 'recon_audit.db'}"
    engine = create_engine(database_url)
    Base.metadata.create_all(engine)
    AuditBase.metadata.create_all(engine)
    monkeypatch.setattr(reconciliation_job, "AuditSession", sessionmaker(bind=engine, expire_on_commit=False))

    event = _event()
    assert EventStore(database_url=database_url).mark_processed(event, tenant_id=tenant_id) is ProcessResult.STORED

    evaluation = {
        "status": "missing_payment",
        "severity": "critical",
        "phase3_status": "UNMATCHED",
        "match": {"match_type": "none"},
        "anomalies": ["missing_payment"],
        "event": event,
        "tenant_id": tenant_id,
        # Evidence with a match_timestamp so _persist_atomically also records the
        # ReconciliationMatch row alongside the audit entry.
        "evidence": {
            "matched_record": None,
            "matching_rules": ["no_matching_candidate"],
            "match_score": 0.0,
            "match_timestamp": "2026-09-12T12:00:00+00:00",
            "engine_version": "phase3.3",
            "rule_version": "phase3.3",
        },
    }
    assert _persist_atomically(event, evaluation, event["TransID"], tenant_id) is ProcessResult.STORED
    return engine, event["TransID"], tenant_id


def test_reconciliation_audit_entry_is_durable_and_tenant_scoped(tmp_path, monkeypatch):
    engine, trans_id, tenant_id = _seed_audited_reconciliation(tmp_path, monkeypatch)
    try:
        with sessionmaker(bind=engine)() as session:
            audits = session.query(ActionAuditEntry).all()
            assert len(audits) == 1
            audit = audits[0]
            # Traceable to the exact reconciliation decision (Rule #34).
            assert audit.tenant_id == tenant_id
            assert audit.actor == "reconciliation_job"
            assert audit.resource_id == trans_id
            assert audit.details["trans_id"] == trans_id
            assert audit.details["status"] == "missing_payment"
            assert audit.details["phase3_status"] == "UNMATCHED"
            assert audit.details["rule_version"] == "phase3.3"
            assert audit.details["evidence"]["matching_rules"] == ["no_matching_candidate"]
            # Ledger + match + audit all committed together (Rule #8).
            assert session.query(ProcessedTransaction).one().reconciliation_status == "completed"
            assert session.query(ReconciliationMatch).count() == 1
    finally:
        engine.dispose()


def test_persisted_reconciliation_audit_cannot_be_updated_at_db_boundary(tmp_path, monkeypatch):
    engine, _trans_id, _tenant_id = _seed_audited_reconciliation(tmp_path, monkeypatch)
    try:
        with sessionmaker(bind=engine)() as session:
            audit_id = session.query(ActionAuditEntry).one().id

        # A direct SQL UPDATE that bypasses the ORM attribute guards must still be
        # rejected by the append-only trigger (Rule #23 defence-in-depth).
        with sessionmaker(bind=engine)() as session:
            try:
                session.execute(
                    ActionAuditEntry.__table__.update()
                    .where(ActionAuditEntry.id == audit_id)
                    .values(actor="tampered", details={"status": "matched"})
                )
                session.commit()
            except Exception as exc:  # noqa: BLE001 - SQLite trigger aborts the statement
                session.rollback()
                assert "append-only" in str(exc).lower()
            else:
                raise AssertionError("reconciliation audit entry was mutated at the DB boundary")

        with sessionmaker(bind=engine)() as session:
            audit = session.get(ActionAuditEntry, audit_id)
            assert audit.actor == "reconciliation_job"
            assert audit.details["status"] == "missing_payment"
    finally:
        engine.dispose()


def test_persisted_reconciliation_audit_cannot_be_deleted_at_db_boundary(tmp_path, monkeypatch):
    engine, _trans_id, _tenant_id = _seed_audited_reconciliation(tmp_path, monkeypatch)
    try:
        with sessionmaker(bind=engine)() as session:
            audit_id = session.query(ActionAuditEntry).one().id

        with sessionmaker(bind=engine)() as session:
            try:
                session.execute(
                    ActionAuditEntry.__table__.delete()
                    .where(ActionAuditEntry.id == audit_id)
                )
                session.commit()
            except Exception as exc:  # noqa: BLE001 - SQLite trigger aborts the statement
                session.rollback()
                assert "append-only" in str(exc).lower()
            else:
                raise AssertionError("reconciliation audit entry was deleted at the DB boundary")

        with sessionmaker(bind=engine)() as session:
            assert session.query(ActionAuditEntry).count() == 1
    finally:
        engine.dispose()
