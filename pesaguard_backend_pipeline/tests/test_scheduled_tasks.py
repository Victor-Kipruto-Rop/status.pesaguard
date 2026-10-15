from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal
import sys
from types import ModuleType

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from models import Base, ProcessedTransaction, Transaction
from scheduler import ScheduledTaskContext, SchedulerError
from scheduled_tasks import archive_tenant_transactions, reconcile_pending_transactions


@pytest.fixture()
def task_session_factory(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'scheduled-tasks.db'}")
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    monkeypatch.setitem(
        sys.modules,
        "reconciliation_job",
        ModuleType("reconciliation_job"),
    )
    reconciliation_job = sys.modules["reconciliation_job"]
    reconciliation_job.AuditSession = factory
    try:
        yield factory
    finally:
        engine.dispose()


def _context(
    *,
    tenant_id="tenant-a",
    trigger_type="scheduled",
    window_start=None,
    window_end=None,
):
    return ScheduledTaskContext(
        tenant_id=tenant_id,
        job_key="scheduled-reconciliation",
        run_id="run-1",
        scheduled_for=datetime(2026, 10, 1, tzinfo=timezone.utc),
        attempt_number=1,
        idempotency_key="scheduler-test-idempotency",
        trigger_type=trigger_type,
        window_start=window_start,
        window_end=window_end,
    )


def _add_transaction(session, suffix, tenant_id, received_at, reconciliation_status):
    transaction_id = f"provider-{suffix}"
    session.add(
        Transaction(
            id=f"transaction-{suffix}",
            trans_id=transaction_id,
            tenant_id=tenant_id,
            provider_account_id="account-1",
            trans_amount=Decimal("25.00"),
            msisdn="254700000000",
            business_short_code="12345",
            trans_time="20261001120000",
            raw_payload={"trans_id": transaction_id},
            created_at=received_at,
        )
    )
    session.add(
        ProcessedTransaction(
            id=f"processed-{suffix}",
            daraja_trans_id=transaction_id,
            tenant_id=tenant_id,
            provider_account_id="account-1",
            received_at=received_at,
            reconciliation_status=reconciliation_status,
        )
    )


def test_reconciliation_handler_selects_only_pending_tenant_rows_in_receipt_window(
    task_session_factory,
    monkeypatch,
):
    start = datetime(2026, 9, 1, tzinfo=timezone.utc)
    middle = start + timedelta(hours=1)
    end = start + timedelta(days=1)
    with task_session_factory() as session:
        _add_transaction(session, "start", "tenant-a", start, "pending")
        _add_transaction(session, "middle", "tenant-a", middle, "failed")
        _add_transaction(session, "end", "tenant-a", end, "pending")
        _add_transaction(session, "foreign", "tenant-b", middle, "pending")
        _add_transaction(session, "done", "tenant-a", middle, "completed")
        session.commit()

    calls = []
    api_jobs = ModuleType("api_core_jobs")
    api_jobs.process_reconciliation_request = lambda tenant_id, transaction_id: (
        calls.append((tenant_id, transaction_id)) or {"status": "completed"}
    )
    monkeypatch.setitem(sys.modules, "api_core_jobs", api_jobs)

    result = reconcile_pending_transactions(
        {"batch_size": 10},
        _context(
            trigger_type="backfill",
            window_start=start,
            window_end=end,
        ),
    )

    assert result == {"selected": 2, "completed": 2}
    assert calls == [
        ("tenant-a", "transaction-start"),
        ("tenant-a", "transaction-middle"),
    ]


@pytest.mark.parametrize("batch_size", [0, 501, True, None])
def test_reconciliation_handler_rejects_unbounded_or_invalid_batch_sizes(
    task_session_factory,
    monkeypatch,
    batch_size,
):
    api_jobs = ModuleType("api_core_jobs")
    api_jobs.process_reconciliation_request = lambda *_: {"status": "completed"}
    monkeypatch.setitem(sys.modules, "api_core_jobs", api_jobs)

    with pytest.raises(SchedulerError, match="batch_size"):
        reconcile_pending_transactions({"batch_size": batch_size}, _context())


def test_backfill_handler_requires_complete_window(task_session_factory, monkeypatch):
    api_jobs = ModuleType("api_core_jobs")
    api_jobs.process_reconciliation_request = lambda *_: {"status": "completed"}
    monkeypatch.setitem(sys.modules, "api_core_jobs", api_jobs)

    with pytest.raises(SchedulerError, match="complete time window"):
        reconcile_pending_transactions(
            {},
            _context(trigger_type="backfill", window_start=None),
        )


def test_reconciliation_handler_fails_if_any_transaction_is_not_completed(
    task_session_factory,
    monkeypatch,
):
    received = datetime(2026, 9, 1, tzinfo=timezone.utc)
    with task_session_factory() as session:
        _add_transaction(session, "failure", "tenant-a", received, "pending")
        session.commit()

    api_jobs = ModuleType("api_core_jobs")
    api_jobs.process_reconciliation_request = lambda *_: {"status": "failed"}
    monkeypatch.setitem(sys.modules, "api_core_jobs", api_jobs)

    with pytest.raises(SchedulerError, match="did not complete"):
        reconcile_pending_transactions({}, _context())


def test_archival_handler_is_tenant_scoped_verified_and_idempotent(
    task_session_factory,
    monkeypatch,
    tmp_path,
):
    from archival import ArchiveManager, ArchiveObjectStore, RetentionPolicy

    engine = task_session_factory.kw["bind"]
    manager = ArchiveManager(
        RetentionPolicy(hot_days=30, warm_days=60, cold_days=180),
        ArchiveObjectStore(str(tmp_path / "objects")),
    )
    now = datetime.now(timezone.utc)
    eligible_time = now - timedelta(days=45)
    too_recent_time = now - timedelta(days=2)
    with task_session_factory() as session:
        for transaction_id, tenant_id, created_at in (
            ("archive-eligible", "tenant-a", eligible_time),
            ("archive-recent", "tenant-a", too_recent_time),
            ("archive-foreign", "tenant-b", eligible_time),
        ):
            session.add(
                Transaction(
                    id=transaction_id,
                    trans_id=transaction_id,
                    tenant_id=tenant_id,
                    provider_account_id="account-1",
                    provider="mpesa",
                    provider_transaction_id=transaction_id,
                    trans_amount=Decimal("25.00"),
                    currency="KES",
                    msisdn="254700000000",
                    business_short_code="12345",
                    trans_time="20261001120000",
                    raw_payload={"source": "provider"},
                    source_schema_version="1.0",
                    lifecycle_stage="STORED",
                    created_at=created_at,
                )
            )
        session.commit()

    monkeypatch.setenv("DATABASE_URL", "sqlite:///unused.db")
    monkeypatch.setattr(
        "database_service.create_database_engine",
        lambda _database_url: engine,
    )
    monkeypatch.setattr(
        ArchiveManager,
        "from_environment",
        classmethod(lambda _cls: manager),
    )

    first = archive_tenant_transactions({"batch_size": 10}, _context())
    second = archive_tenant_transactions({"batch_size": 10}, _context())

    assert first == {"selected": 1, "archived": 1}
    assert second == {"selected": 0, "archived": 0}
    with task_session_factory() as session:
        archived = session.query(Transaction).filter_by(id="archive-eligible").one()
        recent = session.query(Transaction).filter_by(id="archive-recent").one()
        foreign = session.query(Transaction).filter_by(id="archive-foreign").one()
        assert archived.archive_object_key
        assert archived.archived_at is not None
        assert archived.lifecycle_stage == "ARCHIVED"
        assert recent.archive_object_key is None
        assert foreign.archive_object_key is None


def test_archival_handler_alerts_when_transactions_miss_archive_window(
    task_session_factory,
    monkeypatch,
    tmp_path,
):
    from archival import ArchiveManager, ArchiveObjectStore, RetentionPolicy

    engine = task_session_factory.kw["bind"]
    manager = ArchiveManager(
        RetentionPolicy(hot_days=30, warm_days=60, cold_days=180),
        ArchiveObjectStore(str(tmp_path / "objects")),
    )
    old_time = datetime.now(timezone.utc) - timedelta(days=200)
    with task_session_factory() as session:
        session.add(
            Transaction(
                id="archive-window-missed",
                trans_id="archive-window-missed",
                tenant_id="tenant-a",
                provider_account_id="account-1",
                trans_amount=Decimal("25.00"),
                msisdn="254700000000",
                business_short_code="12345",
                trans_time="20260101000000",
                raw_payload={},
                lifecycle_stage="STORED",
                created_at=old_time,
            )
        )
        session.commit()

    monkeypatch.setenv("DATABASE_URL", "sqlite:///unused.db")
    monkeypatch.setattr(
        "database_service.create_database_engine",
        lambda _database_url: engine,
    )
    monkeypatch.setattr(
        ArchiveManager,
        "from_environment",
        classmethod(lambda _cls: manager),
    )

    with pytest.raises(SchedulerError, match="beyond the archive window"):
        archive_tenant_transactions({}, _context())

    with task_session_factory() as session:
        transaction = session.query(Transaction).filter_by(
            id="archive-window-missed"
        ).one()
        assert transaction.archive_object_key is None
        assert transaction.archived_at is None


@pytest.mark.parametrize("batch_size", [0, 501, True, None])
def test_archival_handler_rejects_unbounded_batch_sizes(batch_size):
    with pytest.raises(SchedulerError, match="batch_size"):
        archive_tenant_transactions({"batch_size": batch_size}, _context())
