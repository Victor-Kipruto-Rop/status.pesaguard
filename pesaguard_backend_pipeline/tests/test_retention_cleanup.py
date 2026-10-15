import importlib
import os
import tempfile

from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import pesaguard_backend_pipeline.retention_cleanup as retention_cleanup_mod
from pesaguard_backend_pipeline.models import Base, Discrepancy, ReconciliationOutbox, Transaction, TransactionOutbox
from pesaguard_backend_pipeline.action_audit import ActionAuditEntry, AuditLegalHold
from pesaguard_backend_pipeline.operations import retention_approval


def _stub_external_approval(monkeypatch, consume=None):
    def verify(reference, tenant_id, expected_policy_sha256):
        return retention_approval.VerifiedRetentionApproval(
            approver_id="verified-finance-operator",
            tenant_id=tenant_id or "all",
            policy_sha256=expected_policy_sha256,
            expires_at=datetime.now(timezone.utc) + timedelta(minutes=10),
        )

    monkeypatch.setattr(retention_cleanup_mod.retention_approval, "verify_retention_approval", verify)
    monkeypatch.setattr(
        retention_cleanup_mod.retention_approval,
        "consume_retention_approval",
        consume or (lambda reference, approval, run_id: None),
    )


def test_cleanup_retention_deletes_older_records(monkeypatch):
    with tempfile.TemporaryDirectory() as tmpdir:
        db_path = os.path.join(tmpdir, "retention_test.db")
        monkeypatch.setenv("DATABASE_URL", f"sqlite:///{db_path}")
        monkeypatch.setenv("PESAGUARD_RETENTION_DAYS_TRANSACTIONS", "1")
        monkeypatch.setenv("PESAGUARD_RETENTION_DAYS_DISCREPANCIES", "1")
        monkeypatch.setenv("PESAGUARD_RETENTION_DAYS_AUDIT", "1")

        engine = create_engine(f"sqlite:///{db_path}")
        Base.metadata.create_all(engine)
        Session = sessionmaker(bind=engine, expire_on_commit=False)

        session = Session()
        try:
            old_time = datetime.now(timezone.utc) - timedelta(days=2)
            recent_time = datetime.now(timezone.utc)
            session.add_all([
                Transaction(trans_id="old-tx", tenant_id="tenant-a", trans_amount=10.0, msisdn="254700000000", business_short_code="123", trans_time="20240101120000", raw_payload={}, created_at=old_time),
                Transaction(trans_id="old-unarchived", tenant_id="tenant-a", trans_amount=12.0, msisdn="254700000003", business_short_code="123", trans_time="20240101120000", raw_payload={}, created_at=old_time),
                Transaction(trans_id="old-missing-archive", tenant_id="tenant-a", trans_amount=13.0, msisdn="254700000004", business_short_code="123", trans_time="20240101120000", raw_payload={}, created_at=old_time, lifecycle_stage="ARCHIVED", archived_at=old_time),
                Transaction(trans_id="old-delete", tenant_id="tenant-a", trans_amount=11.0, msisdn="254700000002", business_short_code="123", trans_time="20240101120000", raw_payload={}, created_at=old_time, lifecycle_stage="ARCHIVED", archived_at=old_time, archive_object_key="tier=cold/tenant=tenant-a/old-delete.json"),
                Transaction(trans_id="new-tx", tenant_id="tenant-a", trans_amount=20.0, msisdn="254700000001", business_short_code="123", trans_time="20240101120000", raw_payload={}, created_at=recent_time),
                Discrepancy(id="old-disc", trans_id="old-tx", tenant_id="tenant-a", anomaly_type="missing_payment", status="needs_review", severity="critical", details="old", detected_at=old_time),
                Discrepancy(id="new-disc", trans_id="new-tx", tenant_id="tenant-a", anomaly_type="duplicate", status="needs_review", severity="warning", details="new", detected_at=recent_time),
                ActionAuditEntry(id="old-audit", tenant_id="tenant-a", actor="system", action="cleanup", details={}, created_at=old_time),
                ActionAuditEntry(id="new-audit", tenant_id="tenant-a", actor="system", action="cleanup", details={}, created_at=recent_time),
                TransactionOutbox(id="old-published", tenant_id="tenant-a", event_key="old-published", topic="t", payload={}, status="published", created_at=old_time, available_at=old_time),
                TransactionOutbox(id="old-failed", tenant_id="tenant-a", event_key="old-failed", topic="t", payload={}, status="failed", created_at=old_time, available_at=old_time),
                ReconciliationOutbox(id="old-recon", tenant_id="tenant-a", event_key="old-recon", topic="t", payload={}, status="published", created_at=old_time, available_at=old_time),
                AuditLegalHold(id="hold-old-tx", tenant_id="tenant-a", scope_type="transaction", scope_id="old-tx", reason="investigation", placed_by="test"),
            ])
            session.commit()
        finally:
            session.close()

        retention_cleanup = importlib.reload(retention_cleanup_mod)
        _stub_external_approval(monkeypatch)
        result = retention_cleanup.cleanup_retention(
            approval_reference="RETENTION-TEST-001",
        )
        assert result["deleted_transactions"] == 0
        assert result["transaction_purge_candidates"] == 1
        assert "transactions" in result["skipped_categories"]
        assert len(result["retention_policy_sha256"]) == 64
        assert result["deleted_discrepancies"] == 0
        assert result["deleted_transaction_outbox"] == 1
        assert result["deleted_reconciliation_outbox"] == 1
        assert result["deleted_audit"] == 0
        assert result["eligible_audit_entries"] == 1
        assert result["post_delete_counts"]["transaction_outbox"] == 0
        with Session() as check_session:
            assert check_session.query(Transaction).filter_by(trans_id="old-unarchived").count() == 1
            assert check_session.query(Transaction).filter_by(trans_id="old-missing-archive").count() == 1
            assert check_session.query(Transaction).filter_by(trans_id="old-delete").count() == 1
        engine.dispose()


def test_cleanup_retention_dry_run_counts_archived_rows_without_deleting(
    monkeypatch,
    tmp_path,
):
    engine = create_engine(f"sqlite:///{tmp_path / 'retention-preview.db'}")
    Base.metadata.create_all(engine)
    old_time = datetime.now(timezone.utc) - timedelta(days=2)
    with sessionmaker(bind=engine)() as session:
        session.add(
            Transaction(
                trans_id="preview-tx",
                tenant_id="tenant-preview",
                trans_amount=10,
                msisdn="254700000000",
                business_short_code="123",
                trans_time="20240101120000",
                raw_payload={},
                created_at=old_time,
                archived_at=old_time,
                archive_object_key="tier=cold/tenant=tenant-preview/preview.json",
            )
        )
        session.commit()

    monkeypatch.setattr(retention_cleanup_mod, "RETENTION_DAYS_TRANSACTIONS", 1)
    monkeypatch.setattr(retention_cleanup_mod, "get_engine", lambda: engine)

    result = retention_cleanup_mod.cleanup_retention(
        tenant_id="tenant-preview",
        dry_run=True,
    )

    assert result["deleted_transactions"] == 0
    assert result["transaction_purge_candidates"] == 1
    assert "transactions" in result["skipped_categories"]
    assert len(result["retention_policy_sha256"]) == 64
    with sessionmaker(bind=engine)() as session:
        assert session.query(Transaction).filter_by(trans_id="preview-tx").count() == 1
    engine.dispose()


def test_discrepancy_cleanup_is_disabled_without_an_approved_retention_period(
    monkeypatch,
    tmp_path,
):
    monkeypatch.delenv("PESAGUARD_RETENTION_DAYS_DISCREPANCIES", raising=False)
    retention_cleanup = importlib.reload(retention_cleanup_mod)
    engine = create_engine(f"sqlite:///{tmp_path / 'retention-no-discrepancy-policy.db'}")
    Base.metadata.create_all(engine)
    old_time = datetime.now(timezone.utc) - timedelta(days=4000)
    with sessionmaker(bind=engine)() as session:
        session.add(
            Discrepancy(
                id="unapproved-retention-discrepancy",
                trans_id="transaction-1",
                tenant_id="tenant-retention",
                anomaly_type="missing_payment",
                status="needs_review",
                severity="critical",
                details="must not be purged without an approved period",
                detected_at=old_time,
            )
        )
        session.commit()

    monkeypatch.setattr(retention_cleanup, "get_engine", lambda: engine)
    _stub_external_approval(monkeypatch)
    result = retention_cleanup.cleanup_retention(
        tenant_id="tenant-retention",
        approval_reference="RETENTION-NO-DISCREPANCY-POLICY",
    )

    assert result["deleted_discrepancies"] == 0
    assert result["deleted_reconciliation_outbox"] == 0
    assert "discrepancies" in result["skipped_categories"]
    assert "reconciliation_outbox" in result["skipped_categories"]
    assert result["post_delete_counts"]["discrepancies"] is None
    assert result["retention_windows"]["discrepancies_days"] is None
    with sessionmaker(bind=engine)() as session:
        assert session.query(Discrepancy).filter_by(
            id="unapproved-retention-discrepancy"
        ).count() == 1
    engine.dispose()


def test_transaction_purge_requires_successful_object_verification(
    monkeypatch,
    tmp_path,
):
    engine = create_engine(f"sqlite:///{tmp_path / 'retention-verified-archive.db'}")
    Base.metadata.create_all(engine)
    old_time = datetime.now(timezone.utc) - timedelta(days=2)
    with sessionmaker(bind=engine)() as session:
        session.add(
            Transaction(
                trans_id="verified-archive",
                tenant_id="tenant-archive",
                trans_amount=10,
                msisdn="254700000000",
                business_short_code="123",
                trans_time="20240101120000",
                raw_payload={},
                created_at=old_time,
                archived_at=old_time,
                archive_object_key="tier=cold/tenant=tenant-archive/verified.json",
            )
        )
        session.commit()

    monkeypatch.setenv("PESAGUARD_RETENTION_DAYS_TRANSACTIONS", "1")
    monkeypatch.setattr(retention_cleanup_mod, "get_engine", lambda: engine)
    _stub_external_approval(monkeypatch)
    verified_keys = []

    def verify_archive(transaction):
        verified_keys.append(
            (transaction.tenant_id, transaction.archive_object_key)
        )
        return True

    retention_cleanup = importlib.reload(retention_cleanup_mod)
    monkeypatch.setattr(retention_cleanup, "get_engine", lambda: engine)
    _stub_external_approval(monkeypatch)
    result = retention_cleanup.cleanup_retention(
        tenant_id="tenant-archive",
        approval_reference="RETENTION-VERIFIED-ARCHIVE",
        archive_verifier=verify_archive,
    )

    assert result["deleted_transactions"] == 1
    assert verified_keys == [
        ("tenant-archive", "tier=cold/tenant=tenant-archive/verified.json")
    ]
    with sessionmaker(bind=engine)() as session:
        assert session.query(Transaction).filter_by(trans_id="verified-archive").count() == 0
    engine.dispose()


def test_effective_policy_hash_changes_when_cleanup_window_changes(monkeypatch):
    original = retention_cleanup_mod._effective_retention_policy_sha256()
    monkeypatch.setattr(
        retention_cleanup_mod,
        "RETENTION_DAYS_TRANSACTIONS",
        retention_cleanup_mod.RETENTION_DAYS_TRANSACTIONS + 1,
    )

    assert retention_cleanup_mod._effective_retention_policy_sha256() != original


def test_cleanup_retention_rejects_blank_tenant_before_connecting(monkeypatch):
    monkeypatch.setattr(
        retention_cleanup_mod,
        "get_engine",
        lambda: pytest.fail("invalid tenant input must not open a database connection"),
    )

    with pytest.raises(ValueError, match="tenant_id must not be empty"):
        retention_cleanup_mod.cleanup_retention(tenant_id="  ")


@pytest.mark.parametrize(
    "approval_reference",
    [None, "  "],
)
def test_cleanup_retention_requires_per_run_approval_before_connecting(
    monkeypatch,
    approval_reference,
):
    monkeypatch.setattr(
        retention_cleanup_mod,
        "get_engine",
        lambda: pytest.fail("unapproved cleanup must not open a database connection"),
    )

    with pytest.raises(ValueError, match="approval_reference is required"):
        retention_cleanup_mod.cleanup_retention(
            approval_reference=approval_reference,
        )


def test_cleanup_retention_rejects_policy_that_disables_required_approval(monkeypatch):
    monkeypatch.setattr(retention_cleanup_mod._RETENTION_POLICY, "approval_required", False)
    monkeypatch.setattr(
        retention_cleanup_mod,
        "get_engine",
        lambda: pytest.fail("policy without mandatory approval must not purge data"),
    )

    with pytest.raises(ValueError, match="approval_required=true"):
        retention_cleanup_mod.cleanup_retention(
            approval_reference="RET-123",
        )


def test_cleanup_retention_records_approval_and_completion(monkeypatch, tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'retention-approved.db'}")
    Base.metadata.create_all(engine)
    monkeypatch.setattr(retention_cleanup_mod, "get_engine", lambda: engine)
    _stub_external_approval(monkeypatch)

    result = retention_cleanup_mod.cleanup_retention(
        tenant_id="tenant-approved",
        approval_reference="RET-APPROVAL-123",
    )

    assert result["status"] == "success"
    with sessionmaker(bind=engine)() as session:
        entries = (
            session.query(ActionAuditEntry)
            .filter_by(tenant_id="tenant-approved", action="retention.cleanup")
            .order_by(ActionAuditEntry.created_at)
            .all()
        )
        assert len(entries) == 3
        assert entries[0].outcome == "success"
        assert entries[0].details["phase"] == "approval_verified"
        assert entries[0].details["approved_by"] == "verified-finance-operator"
        assert entries[0].details["approval_reference"] == "RET-APPROVAL-123"
        assert entries[0].details["policy_sha256"]
        assert entries[0].details["run_id"]
        assert entries[1].details["phase"] == "started"
        assert entries[2].outcome == "success"
        assert entries[2].details["phase"] == "completed"
        assert entries[2].details["deleted_transactions"] == 0
    engine.dispose()


def test_cleanup_retention_records_failed_approved_run(monkeypatch, tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'retention-approved-failure.db'}")
    Base.metadata.create_all(engine)
    monkeypatch.setattr(retention_cleanup_mod, "get_engine", lambda: engine)

    def fail_batch(*args, **kwargs):
        raise RuntimeError("retention database failure")

    _stub_external_approval(monkeypatch)
    monkeypatch.setattr(retention_cleanup_mod, "_delete_in_batches", fail_batch)
    with pytest.raises(RuntimeError, match="retention database failure"):
        retention_cleanup_mod.cleanup_retention(
            tenant_id="tenant-approved",
            approval_reference="RET-APPROVAL-456",
        )

    with sessionmaker(bind=engine)() as session:
        entries = (
            session.query(ActionAuditEntry)
            .filter_by(tenant_id="tenant-approved", action="retention.cleanup")
            .order_by(ActionAuditEntry.created_at)
            .all()
        )
        assert [entry.outcome for entry in entries] == ["success", "success", "failure"]
        assert entries[2].details["phase"] == "failed"
        assert entries[2].details["error_type"] == "RuntimeError"
    engine.dispose()


def test_cleanup_retention_rolls_back_and_reraises_database_errors(monkeypatch, tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'retention-failure.db'}")
    Base.metadata.create_all(engine)
    monkeypatch.setattr(retention_cleanup_mod, "get_engine", lambda: engine)

    def fail_batch(*args, **kwargs):
        raise RuntimeError("retention database failure")

    _stub_external_approval(monkeypatch)
    monkeypatch.setattr(retention_cleanup_mod, "_delete_in_batches", fail_batch)
    with pytest.raises(RuntimeError, match="retention database failure"):
        retention_cleanup_mod.cleanup_retention(
            approval_reference="RETENTION-FAILURE-001",
        )
    engine.dispose()


def test_cleanup_retention_refuses_unverified_approval_before_connecting(monkeypatch):
    monkeypatch.setattr(
        retention_cleanup_mod.retention_approval,
        "verify_retention_approval",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            retention_approval.RetentionApprovalError("approval service unavailable")
        ),
    )
    monkeypatch.setattr(
        retention_cleanup_mod,
        "get_engine",
        lambda: pytest.fail("unverified approval must not open the database"),
    )

    with pytest.raises(retention_approval.RetentionApprovalError, match="unavailable"):
        retention_cleanup_mod.cleanup_retention(approval_reference="RET-UNVERIFIED")


def test_cleanup_retention_audits_consumption_failure_without_deleting(monkeypatch, tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'retention-consume-failure.db'}")
    Base.metadata.create_all(engine)
    monkeypatch.setattr(retention_cleanup_mod, "get_engine", lambda: engine)

    def reject_consumption(*args, **kwargs):
        raise retention_approval.RetentionApprovalError("approval consume failed")

    _stub_external_approval(monkeypatch, consume=reject_consumption)
    delete_calls = []
    monkeypatch.setattr(
        retention_cleanup_mod,
        "_delete_in_batches",
        lambda *args, **kwargs: delete_calls.append(args) or 0,
    )

    with pytest.raises(retention_approval.RetentionApprovalError, match="consume failed"):
        retention_cleanup_mod.cleanup_retention(
            tenant_id="tenant-approved",
            approval_reference="RET-APPROVAL-CONSUME-FAIL",
        )

    assert delete_calls == []
    with sessionmaker(bind=engine)() as session:
        entries = (
            session.query(ActionAuditEntry)
            .filter_by(tenant_id="tenant-approved", action="retention.cleanup")
            .order_by(ActionAuditEntry.created_at)
            .all()
        )
        assert [entry.details["phase"] for entry in entries] == ["approval_verified", "failed"]
        assert entries[1].outcome == "failure"
    engine.dispose()
