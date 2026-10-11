"""Unit + integration tests for the durable scheduler.

Verifies determinism (injected clock), at-most-once execution across workers via
the distributed lock, tenant isolation, failure accounting, and re-arm behaviour.
"""

from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from models import Base, ScheduledJob
from scheduler import Scheduler, SchedulerError, UnknownTaskError, as_utc


class AlwaysAvailableLock:
    """Lock stub that always grants (single-worker semantics)."""

    def acquire(self, name, ttl_seconds=30.0):
        return f"tok-{name}"

    def release(self, name, token):
        return True


class AlwaysBusyLock:
    """Lock stub that never grants (another worker owns the run)."""

    def acquire(self, name, ttl_seconds=30.0):
        return None

    def release(self, name, token):
        return False


@pytest.fixture()
def session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    with Session() as s:
        yield s
        s.rollback()


def test_register_is_idempotent_per_tenant_job(session):
    sch = Scheduler(session, lock=AlwaysAvailableLock())
    a = sch.register("tenant-a", "nightly", "reconcile", 3600)
    b = sch.register("tenant-a", "nightly", "reconcile", 3600)
    assert a.id == b.id
    assert session.query(ScheduledJob).count() == 1


def test_register_rejects_non_positive_interval(session):
    sch = Scheduler(session, lock=AlwaysAvailableLock())
    with pytest.raises(SchedulerError):
        sch.register("tenant-a", "bad", "task", 0)


def test_deterministic_due_selection_with_injected_clock(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    sch = Scheduler(session, lock=AlwaysAvailableLock(), handlers={"t": lambda p: None})
    sch.register("tenant-a", "j", "t", 3600, first_run_at=base)

    # Not due before next_run_at.
    assert sch.due_jobs(now=base - timedelta(seconds=1)) == []
    # Due exactly at next_run_at.
    assert len(sch.due_jobs(now=base)) == 1


def test_run_due_executes_once_and_rearms(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    calls = []
    sch = Scheduler(session, lock=AlwaysAvailableLock(),
                    handlers={"t": lambda p: calls.append(p)})
    sch.register("tenant-a", "j", "t", 3600, first_run_at=base)

    outcomes = sch.run_due(now=base)
    assert outcomes[0]["status"] == "ran"
    assert len(calls) == 1
    job = session.query(ScheduledJob).one()
    assert job.run_count == 1
    # Re-armed exactly one interval after the run. SQLite drops tzinfo on
    # round-trip, so normalize before comparing (auth_rbac convention).
    assert as_utc(job.next_run_at) == base + timedelta(seconds=3600)

    # Immediately re-ticking does not run again.
    assert sch.run_due(now=base) == []


def test_busy_lock_skips_without_double_execution(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    calls = []
    sch = Scheduler(session, lock=AlwaysBusyLock(),
                    handlers={"t": lambda p: calls.append(p)})
    sch.register("tenant-a", "j", "t", 3600, first_run_at=base)

    outcomes = sch.run_due(now=base)
    assert outcomes[0]["status"] == "skipped_locked"
    assert calls == []  # never executed by this worker
    # Job was NOT re-armed, so it remains due for the owner worker.
    job = session.query(ScheduledJob).one()
    assert as_utc(job.next_run_at) == base


def test_missing_handler_records_failure_and_rearms(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    sch = Scheduler(session, lock=AlwaysAvailableLock(), handlers={})
    sch.register("tenant-a", "j", "does-not-exist", 3600, first_run_at=base)

    outcomes = sch.run_due(now=base)
    assert outcomes[0]["status"] == "failed"
    job = session.query(ScheduledJob).one()
    assert job.failure_count == 1
    assert job.last_error and "UnknownTaskError" in job.last_error
    assert as_utc(job.next_run_at) == base + timedelta(seconds=3600)  # still re-armed


def test_disabled_job_is_never_due(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    sch = Scheduler(session, lock=AlwaysAvailableLock(), handlers={"t": lambda p: None})
    sch.register("tenant-a", "j", "t", 3600, first_run_at=base)
    sch.set_enabled("tenant-a", "j", False)
    assert sch.due_jobs(now=base) == []


def test_tenant_isolation_jobs_are_independent(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    sch = Scheduler(session, lock=AlwaysAvailableLock(), handlers={"t": lambda p: None})
    sch.register("tenant-a", "j", "t", 3600, first_run_at=base)
    sch.register("tenant-b", "j", "t", 3600, first_run_at=base)
    # Same job_key across tenants coexist (unique per tenant).
    assert session.query(ScheduledJob).count() == 2
    outcomes = sch.run_due(now=base)
    tenants = {o["tenant_id"] for o in outcomes}
    assert tenants == {"tenant-a", "tenant-b"}
