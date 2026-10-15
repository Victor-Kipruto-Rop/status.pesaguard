"""Unit + integration tests for the durable scheduler.

Verifies determinism (injected clock), at-most-once execution across workers via
the distributed lock, tenant isolation, failure accounting, and re-arm behaviour.
"""

from datetime import datetime, timedelta, timezone
import time

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from models import Base, ScheduledJob, ScheduledJobRun
from scheduler import Scheduler, SchedulerError, UnknownTaskError, as_utc


def _sleep_for_payload(payload, context):
    time.sleep(payload["sleep_seconds"])


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


def test_register_rejects_conflicting_definition_for_existing_key(session):
    sch = Scheduler(session, lock=AlwaysAvailableLock())
    sch.register(
        "tenant-a",
        "nightly",
        "reconcile",
        3600,
        payload={"batch_size": 50},
        max_attempts=2,
        retry_backoff_seconds=15,
        timeout_seconds=120,
    )

    with pytest.raises(SchedulerError, match="different configuration"):
        sch.register(
            "tenant-a",
            "nightly",
            "reconcile",
            1800,
            payload={"batch_size": 25},
            max_attempts=3,
            retry_backoff_seconds=30,
            timeout_seconds=60,
        )


def test_register_rejects_non_positive_interval(session):
    sch = Scheduler(session, lock=AlwaysAvailableLock())
    with pytest.raises(SchedulerError):
        sch.register("tenant-a", "bad", "task", 0)


@pytest.mark.parametrize(
    ("max_attempts", "retry_backoff_seconds"),
    [(0, 10), (6, 10), (2, 0), (2, 3601)],
)
def test_register_rejects_unbounded_retry_policy(
    session, max_attempts, retry_backoff_seconds
):
    sch = Scheduler(session, lock=AlwaysAvailableLock())

    with pytest.raises(SchedulerError):
        sch.register(
            "tenant-a",
            "bad-policy",
            "task",
            60,
            max_attempts=max_attempts,
            retry_backoff_seconds=retry_backoff_seconds,
        )


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
    run = session.query(ScheduledJobRun).one()
    assert run.tenant_id == "tenant-a"
    assert run.job_key == "j"
    assert run.status == "succeeded"
    assert run.attempt_number == 1


def test_failed_scheduled_run_retries_with_bounded_backoff_and_keeps_history(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    attempts = []

    def fails_once(payload):
        attempts.append(payload)
        if len(attempts) == 1:
            raise RuntimeError("temporary dependency failure")

    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={"t": fails_once},
    )
    sch.register(
        "tenant-a",
        "retry-job",
        "t",
        3600,
        first_run_at=base,
        payload={"kind": "reconcile"},
        max_attempts=2,
        retry_backoff_seconds=10,
    )

    first = sch.run_due(now=base)
    assert first[0]["status"] == "retrying"
    job = session.query(ScheduledJob).one()
    assert as_utc(job.next_run_at) == base
    assert as_utc(job.retry_at) == base + timedelta(seconds=10)
    assert sch.run_due(now=base + timedelta(seconds=9)) == []

    second = sch.run_due(now=base + timedelta(seconds=10))
    assert second[0]["status"] == "ran"
    assert len(attempts) == 2
    assert attempts == [{"kind": "reconcile"}, {"kind": "reconcile"}]

    runs = (
        session.query(ScheduledJobRun)
        .order_by(ScheduledJobRun.attempt_number)
        .all()
    )
    assert [run.attempt_number for run in runs] == [1, 2]
    assert [run.status for run in runs] == ["failed", "succeeded"]
    assert all(as_utc(run.scheduled_for) == base for run in runs)
    assert all(run.finished_at is not None for run in runs)
    assert job.run_count == 1
    assert job.failure_count == 1
    assert job.retry_at is None
    assert as_utc(job.next_run_at) == base + timedelta(seconds=10) + timedelta(seconds=3600)


def test_scheduled_retry_reuses_idempotency_key_across_attempts(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    contexts = []

    def fails_once(payload, context):
        contexts.append(context)
        if len(contexts) == 1:
            raise RuntimeError("temporary failure")

    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={"task": fails_once},
    )
    sch.register(
        "tenant-a",
        "idempotent-job",
        "task",
        3600,
        first_run_at=base,
        max_attempts=2,
        retry_backoff_seconds=5,
    )

    assert sch.run_due(now=base)[0]["status"] == "retrying"
    assert sch.run_due(now=base + timedelta(seconds=5))[0]["status"] == "ran"

    assert contexts[0].idempotency_key == contexts[1].idempotency_key
    assert contexts[0].run_id != contexts[1].run_id
    assert [context.attempt_number for context in contexts] == [1, 2]


def test_retry_exhaustion_advances_schedule_and_alerts_operator(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    alerts = []

    def fail(payload):
        raise RuntimeError("unavailable")

    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={"t": fail},
        on_failure=lambda details: alerts.append(details),
    )
    sch.register(
        "tenant-a",
        "exhausted-job",
        "t",
        60,
        first_run_at=base,
        max_attempts=3,
        retry_backoff_seconds=5,
    )

    first = sch.run_due(now=base)
    assert first[0]["status"] == "retrying"
    second = sch.run_due(now=base + timedelta(seconds=5))
    assert second[0]["status"] == "retrying"
    assert sch.run_due(now=base + timedelta(seconds=14)) == []
    terminal = sch.run_due(now=base + timedelta(seconds=15))

    assert terminal[0]["status"] == "failed"
    job = session.query(ScheduledJob).one()
    assert job.failure_count == 3
    assert job.retry_at is None
    assert as_utc(job.next_run_at) == base + timedelta(seconds=15) + timedelta(seconds=60)
    assert len(alerts) == 1
    assert alerts[0]["tenant_id"] == "tenant-a"
    assert alerts[0]["job_key"] == "exhausted-job"
    assert alerts[0]["attempts"] == 3
    assert session.query(ScheduledJobRun).count() == 3


def test_run_history_is_tenant_scoped_and_bounded(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={"t": lambda payload: None},
    )
    sch.register("tenant-a", "same-key", "t", 60, first_run_at=base)
    sch.register("tenant-b", "same-key", "t", 60, first_run_at=base)
    sch.run_due(now=base)

    assert len(sch.run_history("tenant-a", "same-key", limit=1)) == 1
    assert len(sch.run_history("tenant-b", "same-key", limit=1)) == 1
    assert sch.run_history("tenant-c", "same-key") == []
    with pytest.raises(SchedulerError, match="limit"):
        sch.run_history("tenant-a", "same-key", limit=501)


def test_interrupted_attempt_requires_operator_recovery_instead_of_automatic_replay(
    session,
):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    calls = []
    alerts = []
    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={"t": lambda payload: calls.append(payload)},
        on_failure=alerts.append,
    )
    job = sch.register("tenant-a", "interrupted", "t", 60, first_run_at=base)
    session.commit()
    session.add(
        ScheduledJobRun(
            job_id=job.id,
            tenant_id="tenant-a",
            job_key="interrupted",
            scheduled_for=base,
            attempt_number=1,
            status="running",
            worker_id="worker-before-crash",
            started_at=base,
        )
    )
    session.commit()

    result = sch.run_due(now=base + timedelta(minutes=5))

    assert result[0]["status"] == "recovery_required"
    assert calls == []
    assert alerts[0]["status"] == "recovery_required"
    interrupted = session.query(ScheduledJobRun).one()
    assert interrupted.status == "interrupted"

    repeated = sch.run_due(now=base + timedelta(minutes=6))
    assert repeated[0]["status"] == "recovery_required"
    assert len(alerts) == 1
    assert session.query(ScheduledJobRun).count() == 1


def test_interrupted_run_requires_explicit_idempotent_operator_replay(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    calls = []
    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={"t": lambda payload: calls.append(payload)},
    )
    job = sch.register(
        "tenant-a",
        "resume-job",
        "t",
        60,
        first_run_at=base,
        payload={"event_id": "stable-1"},
        max_attempts=2,
        retry_backoff_seconds=5,
    )
    session.commit()
    run = ScheduledJobRun(
        job_id=job.id,
        tenant_id="tenant-a",
        job_key="resume-job",
        scheduled_for=base,
        attempt_number=1,
        status="running",
        worker_id="crashed-worker",
        started_at=base,
    )
    session.add(run)
    session.commit()
    sch.run_due(now=base + timedelta(minutes=5))

    with pytest.raises(SchedulerError, match="interrupted run not found for tenant"):
        sch.resolve_interrupted_run(
            "tenant-b",
            run.id,
            resolution="skip",
            operator_id="operator-1",
            reason="must not see another tenant's run",
            now=base + timedelta(minutes=6),
        )

    with pytest.raises(SchedulerError, match="explicit idempotent-replay confirmation"):
        sch.resolve_interrupted_run(
            "tenant-a",
            run.id,
            resolution="retry",
            operator_id="operator-1",
            reason="checked",
        )

    sch.resolve_interrupted_run(
        "tenant-a",
        run.id,
        resolution="retry",
        operator_id="operator-1",
        reason="verified downstream idempotency key",
        confirm_idempotent_replay=True,
        now=base + timedelta(minutes=6),
    )
    result = sch.run_due(now=base + timedelta(minutes=6))

    assert result[0]["status"] == "ran"
    assert calls == [{"event_id": "stable-1"}]
    previous, replay = (
        session.query(ScheduledJobRun)
        .order_by(ScheduledJobRun.attempt_number)
        .all()
    )
    assert previous.status == "failed"
    assert previous.error_type == "OperatorAuthorizedReplay"
    assert previous.resolution_by == "operator-1"
    assert previous.resolution_reason == "verified downstream idempotency key"
    assert replay.status == "succeeded"


def test_interrupted_run_operator_skip_resumes_schedule_without_replay(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    calls = []
    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={"t": lambda payload: calls.append(payload)},
    )
    job = sch.register("tenant-a", "skip-job", "t", 60, first_run_at=base)
    session.commit()
    run = ScheduledJobRun(
        job_id=job.id,
        tenant_id="tenant-a",
        job_key="skip-job",
        scheduled_for=base,
        attempt_number=1,
        status="running",
        worker_id="crashed-worker",
        started_at=base,
    )
    session.add(run)
    session.commit()
    sch.run_due(now=base + timedelta(minutes=5))

    sch.resolve_interrupted_run(
        "tenant-a",
        run.id,
        resolution="skip",
        operator_id="operator-1",
        reason="reconciled externally; no replay required",
        now=base + timedelta(minutes=6),
    )

    assert calls == []
    assert run.status == "failed"
    assert run.error_type == "OperatorSkippedInterruptedRun"
    assert run.resolution_by == "operator-1"
    assert as_utc(job.next_run_at) == base + timedelta(minutes=7)
    assert job.retry_at is None


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
    sch.register(
        "tenant-a",
        "j",
        "does-not-exist",
        3600,
        first_run_at=base,
        max_attempts=1,
    )

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


def test_scheduler_can_select_due_jobs_for_one_tenant(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={"t": lambda payload: None},
    )
    sch.register("tenant-a", "same", "t", 60, first_run_at=base)
    sch.register("tenant-b", "same", "t", 60, first_run_at=base)

    outcomes = sch.run_due(now=base, tenant_id="tenant-a")

    assert [outcome["tenant_id"] for outcome in outcomes] == ["tenant-a"]
    assert session.query(ScheduledJob).filter_by(tenant_id="tenant-b").one().run_count == 0


def test_dependency_graph_runs_in_order_and_blocks_unmet_children(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    calls = []
    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={
            "parent": lambda payload: calls.append("parent"),
            "child": lambda payload: calls.append("child"),
        },
    )
    sch.register("tenant-a", "parent", "parent", 60, first_run_at=base)
    sch.register("tenant-a", "child", "child", 60, first_run_at=base)
    sch.add_dependency("tenant-a", "child", "parent")

    results = sch.run_due(now=base)

    assert calls == ["parent", "child"]
    assert [result["status"] for result in results] == ["ran", "ran"]
    assert sch.dependencies("tenant-a", "child") == ["parent"]


def test_dependency_child_stays_blocked_until_parent_succeeds(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    calls = []

    def fail_parent_once(payload):
        calls.append("parent")
        if calls.count("parent") == 1:
            raise RuntimeError("provider unavailable")

    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={
            "parent": fail_parent_once,
            "child": lambda payload: calls.append("child"),
        },
    )
    sch.register(
        "tenant-a",
        "parent",
        "parent",
        60,
        first_run_at=base,
        max_attempts=2,
        retry_backoff_seconds=10,
    )
    sch.register("tenant-a", "child", "child", 60, first_run_at=base)
    sch.add_dependency("tenant-a", "child", "parent")

    first = sch.run_due(now=base)
    assert [result["status"] for result in first] == [
        "retrying",
        "waiting_dependency",
    ]
    assert calls == ["parent"]

    second = sch.run_due(now=base + timedelta(seconds=10))
    assert [result["status"] for result in second] == ["ran", "ran"]
    assert calls == ["parent", "parent", "child"]


def test_dependency_graph_rejects_cycles_and_misaligned_schedules(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    sch = Scheduler(session, lock=AlwaysAvailableLock())
    sch.register("tenant-a", "first", "task", 60, first_run_at=base)
    sch.register("tenant-a", "second", "task", 60, first_run_at=base)
    sch.register("tenant-a", "other", "task", 120, first_run_at=base)

    sch.add_dependency("tenant-a", "second", "first")
    with pytest.raises(SchedulerError, match="cycle"):
        sch.add_dependency("tenant-a", "first", "second")
    with pytest.raises(SchedulerError, match="same interval"):
        sch.add_dependency("tenant-a", "other", "first")


def test_backfill_is_idempotent_and_passes_stable_window_context(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    start = datetime(2026, 9, 1, tzinfo=timezone.utc)
    end = datetime(2026, 9, 2, tzinfo=timezone.utc)
    contexts = []

    def run_backfill(payload, context):
        contexts.append(context)

    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={"reconcile": run_backfill},
    )
    sch.register("tenant-a", "reconcile", "reconcile", 3600, first_run_at=base)
    backfill = sch.enqueue_backfill(
        "tenant-a",
        "reconcile",
        start,
        end,
        idempotency_key="backfill-september",
        requested_by="operator-1",
        reason="reconcile missing provider records",
    )
    repeated = sch.enqueue_backfill(
        "tenant-a",
        "reconcile",
        start,
        end,
        idempotency_key="backfill-september",
        requested_by="operator-1",
        reason="reconcile missing provider records",
    )
    assert repeated.id == backfill.id

    outcomes = sch.run_due(now=base)

    assert outcomes[0]["status"] == "backfill_succeeded"
    assert contexts[0].window_start == start
    assert contexts[0].window_end == end
    assert contexts[0].idempotency_key == (
        "scheduler-backfill:tenant-a:reconcile:backfill-september"
    )
    assert backfill.status == "succeeded"
    assert backfill.attempt_count == 1
    assert session.query(ScheduledJobRun).one().trigger_type == "backfill"


def test_backfill_idempotency_key_cannot_be_reused_for_different_window(session):
    start = datetime(2026, 9, 1, tzinfo=timezone.utc)
    end = datetime(2026, 9, 2, tzinfo=timezone.utc)
    sch = Scheduler(session, lock=AlwaysAvailableLock())
    sch.register("tenant-a", "reconcile", "reconcile", 3600)
    sch.enqueue_backfill(
        "tenant-a", "reconcile", start, end,
        idempotency_key="same-key", requested_by="operator-1", reason="recovery",
    )

    with pytest.raises(SchedulerError, match="different request"):
        sch.enqueue_backfill(
            "tenant-a", "reconcile", start, end + timedelta(days=1),
            idempotency_key="same-key", requested_by="operator-1", reason="recovery",
        )


def test_backfill_retry_keeps_request_idempotency_and_attempt_history(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    start = datetime(2026, 9, 1, tzinfo=timezone.utc)
    end = datetime(2026, 9, 2, tzinfo=timezone.utc)
    contexts = []

    def fails_once(payload, context):
        contexts.append(context)
        if len(contexts) == 1:
            raise RuntimeError("temporary failure")

    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={"reconcile": fails_once},
    )
    sch.register(
        "tenant-a",
        "reconcile",
        "reconcile",
        3600,
        first_run_at=base,
        max_attempts=2,
        retry_backoff_seconds=5,
    )
    request = sch.enqueue_backfill(
        "tenant-a",
        "reconcile",
        start,
        end,
        idempotency_key="backfill-retry-september",
        requested_by="operator-1",
        reason="resume a verified backfill",
    )

    assert sch.run_due(now=base)[0]["status"] == "backfill_retrying"
    assert request.status == "retrying"
    assert request.attempt_count == 1
    assert sch.run_due(now=base + timedelta(seconds=4))[0]["status"] == "waiting_backfill_retry"
    assert sch.run_due(now=base + timedelta(seconds=5))[0]["status"] == "backfill_succeeded"

    assert contexts[0].idempotency_key == contexts[1].idempotency_key
    assert contexts[0].window_start == contexts[1].window_start == start
    assert contexts[0].window_end == contexts[1].window_end == end
    assert [context.attempt_number for context in contexts] == [1, 2]
    runs = (
        session.query(ScheduledJobRun)
        .order_by(ScheduledJobRun.attempt_number)
        .all()
    )
    assert [run.status for run in runs] == ["failed", "succeeded"]


def test_execution_timeout_terminates_handler_child_process(session):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    alerts = []
    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={"sleep": _sleep_for_payload},
        on_failure=alerts.append,
    )
    sch.register(
        "tenant-a",
        "bounded-task",
        "sleep",
        60,
        first_run_at=base,
        max_attempts=1,
        timeout_seconds=1,
        payload={"sleep_seconds": 5},
    )

    outcome = sch.run_due(now=base)[0]

    assert outcome["status"] == "failed"
    assert outcome["error_type"] == "SchedulerTimeoutError"
    assert session.query(ScheduledJobRun).one().status == "failed"
    assert alerts[0]["error_type"] == "SchedulerTimeoutError"


@pytest.mark.parametrize(
    ("timeout_seconds", "accepts_context"),
    [
        (None, True),
        (1, False),
    ],
)
def test_production_scheduler_fails_closed_on_unsafe_handler_configuration(
    session,
    monkeypatch,
    timeout_seconds,
    accepts_context,
):
    base = datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc)
    monkeypatch.setenv("PESAGUARD_ENVIRONMENT", "production")
    calls = []

    def context_handler(payload, context):
        calls.append(context)

    def legacy_handler(payload):
        calls.append(payload)

    sch = Scheduler(
        session,
        lock=AlwaysAvailableLock(),
        handlers={"task": context_handler if accepts_context else legacy_handler},
    )
    sch.register(
        "tenant-a",
        "unsafe-job",
        "task",
        60,
        first_run_at=base,
        max_attempts=1,
        timeout_seconds=timeout_seconds,
    )

    outcome = sch.run_due(now=base)[0]

    assert outcome["status"] == "failed"
    assert session.query(ScheduledJobRun).one().status == "failed"
    assert session.query(ScheduledJobRun).one().error_type == "SchedulerError"
    assert calls == []
