"""Async job reliability regression tests (services 82 & 83).

Services 78-81 (job/scheduler/worker, queue) are covered by
test_scheduler.py, test_infra_locking_scheduling.py, test_distributed_lock.py,
test_phase_manager.py. Two helpers that underpin retry and dead-letter behavior
for provider calls and RQ jobs had NO direct tests before this file:

  - Service 82 (Retry): ``utils.retries.retry_with_backoff`` — the exponential-backoff
    helper the Daraja OAuth client uses before giving up. Untested: retry count,
    backoff schedule, jitter strategies, argument validation.
  - Service 83 (DLQ): ``background_tasks.handle_job_failure`` (RQ failure hook that
    persists a failed job into ``DeadLetter``) and
    ``background_tasks.replay_dead_letter_job`` (tenant-scoped DeadLetter replay).

All behaviours below were verified against the current implementation first
(Rule #62). No production code is changed here.
"""

from __future__ import annotations

import os

import pytest

# background_tasks requires DATABASE_URL at import and binds a module-level engine.
os.environ.setdefault("DATABASE_URL", "sqlite:///:memory:")

from utils.retries import retry_with_backoff  # noqa: E402


# --- Service 82: retry_with_backoff ------------------------------------------------


def test_retry_returns_first_success_without_sleeping(monkeypatch):
    sleeps = []
    monkeypatch.setattr("time.sleep", lambda seconds: sleeps.append(seconds))
    calls = {"n": 0}

    def fn():
        calls["n"] += 1
        return "ok"

    assert retry_with_backoff(fn) == "ok"
    assert calls["n"] == 1
    assert sleeps == []


def test_retry_retries_then_returns_success(monkeypatch):
    sleeps = []
    monkeypatch.setattr("time.sleep", lambda seconds: sleeps.append(seconds))
    calls = {"n": 0}

    def fn():
        calls["n"] += 1
        if calls["n"] < 3:
            raise ConnectionError("flaky provider")
        return "recovered"

    assert retry_with_backoff(fn, retries=5, base=1.0, jitter="none") == "recovered"
    assert calls["n"] == 3
    # Deterministic backoff with jitter disabled: base * 2**attempt.
    assert sleeps == [1.0, 2.0]


def test_retry_raises_original_error_after_exhaustion(monkeypatch):
    monkeypatch.setattr("time.sleep", lambda seconds: None)
    calls = {"n": 0}

    def fn():
        calls["n"] += 1
        raise TimeoutError("provider down")

    with pytest.raises(TimeoutError, match="provider down"):
        retry_with_backoff(fn, retries=2, base=0.01, jitter="none")
    # Initial attempt + exactly ``retries`` retries.
    assert calls["n"] == 3


def test_retry_backoff_is_capped_and_jittered_within_cap(monkeypatch):
    sleeps = []
    monkeypatch.setattr("time.sleep", lambda seconds: sleeps.append(seconds))
    random_values = iter([0.0, 1.0])
    monkeypatch.setattr("random.random", lambda: next(random_values, 0.5))
    calls = {"n": 0}

    def fn():
        calls["n"] += 1
        if calls["n"] < 3:
            raise RuntimeError("boom")
        return "ok"

    assert retry_with_backoff(fn, retries=5, base=100.0, max_backoff=30.0, jitter="full") == "ok"
    assert sleeps[0] == 0.0 * 30.0  # full jitter can sleep ~0
    assert sleeps[1] == 1.0 * 30.0  # bounded by the 30s cap, not base * 2**1
    assert all(0.0 <= value <= 30.0 for value in sleeps)


def test_retry_rejects_negative_retries():
    with pytest.raises(ValueError, match="retries must be >= 0"):
        retry_with_backoff(lambda: None, retries=-1)


def test_retry_rejects_unsupported_jitter_strategy(monkeypatch):
    monkeypatch.setattr("time.sleep", lambda seconds: None)

    def fn():
        raise RuntimeError("boom")

    with pytest.raises(ValueError, match="unsupported jitter strategy"):
        retry_with_backoff(fn, retries=3, jitter="half")


# --- Service 83: handle_job_failure (RQ failure -> DeadLetter) --------------------

from sqlalchemy import create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402

import background_tasks  # noqa: E402
from background_tasks import handle_job_failure, replay_dead_letter_job  # noqa: E402
from models import Base, DeadLetter  # noqa: E402


class _FakeJob:
    """Minimal RQ-job double exposing the attributes handle_job_failure reads."""

    def __init__(self, job_id, func_name, args):
        self.id = job_id
        self.func_name = func_name
        self.args = args


@pytest.fixture()
def task_db(tmp_path, monkeypatch):
    database_url = f"sqlite:///{tmp_path / 'task-queue.db'}"
    engine = create_engine(database_url)
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    monkeypatch.setattr(background_tasks, "TaskSessionLocal", factory)
    yield engine, factory
    engine.dispose()


def test_failed_job_is_persisted_to_dead_letter_with_tenant(task_db):
    _, factory = task_db
    payload = {"tenant_id": "tenant-a", "trans_id": "TX-1"}
    job = _FakeJob("job-1", "publish_transaction_event", ["topic", payload])

    handle_job_failure(job, None, RuntimeError, RuntimeError("kafka down"), None)

    with factory() as session:
        row = session.query(DeadLetter).filter_by(id="dlq_job_job-1").one()
        assert row.tenant_id == "tenant-a"
        assert row.reason == "background_job_failed"
        assert "kafka down" in (row.error_detail or "")
        assert row.payload == payload


def test_failed_job_without_dict_payload_falls_back_safely(task_db):
    _, factory = task_db
    job = _FakeJob("job-2", "publish_transaction_event", ["topic"])

    handle_job_failure(job, None, ValueError, ValueError("bad"), None)

    with factory() as session:
        row = session.query(DeadLetter).filter_by(id="dlq_job_job-2").one()
        assert row.tenant_id == "default"
        assert row.reason == "background_job_failed"


# --- Service 83: replay_dead_letter_job (tenant-scoped replay) --------------------


def _seed_dead_letter(factory, **overrides):
    values = {
        "id": "dlq-replay-1",
        "tenant_id": "tenant-a",
        "reason": "background_job_failed",
        "payload": {"trans_id": "TX-9"},
        "replay_status": "queued",
    }
    values.update(overrides)
    with factory() as session:
        session.add(DeadLetter(**values))
        session.commit()


@pytest.fixture()
def replay_db(tmp_path, monkeypatch):
    database_url = f"sqlite:///{tmp_path / 'replay.db'}"
    engine = create_engine(database_url)
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    monkeypatch.setattr(background_tasks, "TaskSessionLocal", factory)
    monkeypatch.setattr(background_tasks, "_task_db_engine", engine)
    yield engine, factory
    engine.dispose()


def test_replay_publishes_and_marks_processed(replay_db, monkeypatch):
    _, factory = replay_db
    _seed_dead_letter(factory)
    published = []
    monkeypatch.setattr(
        "producer.publish_transaction_event",
        lambda topic, payload: published.append((topic, payload)),
    )

    replay_dead_letter_job("dlq-replay-1", "tenant-a")

    assert published and published[0][1] == {"trans_id": "TX-9"}
    with factory() as session:
        row = session.query(DeadLetter).filter_by(id="dlq-replay-1").one()
        assert row.replay_status == "published"
        assert row.processed is True
        assert row.processed_at is not None


def test_replay_is_tenant_scoped(replay_db, monkeypatch):
    _, factory = replay_db
    _seed_dead_letter(factory)
    published = []
    monkeypatch.setattr(
        "producer.publish_transaction_event",
        lambda topic, payload: published.append((topic, payload)),
    )

    # Wrong tenant: must be a no-op, nothing published, row untouched.
    replay_dead_letter_job("dlq-replay-1", "tenant-b")

    assert published == []
    with factory() as session:
        row = session.query(DeadLetter).filter_by(id="dlq-replay-1").one()
        assert row.replay_status == "queued"
        assert row.processed is not True


def test_replay_ignores_non_queued_entries(replay_db, monkeypatch):
    _, factory = replay_db
    _seed_dead_letter(factory, id="dlq-idle-1", replay_status="idle")
    published = []
    monkeypatch.setattr(
        "producer.publish_transaction_event",
        lambda topic, payload: published.append((topic, payload)),
    )

    replay_dead_letter_job("dlq-idle-1", "tenant-a")

    assert published == []
    with factory() as session:
        row = session.query(DeadLetter).filter_by(id="dlq-idle-1").one()
        assert row.replay_status == "idle"


def test_replay_failure_marks_failed_preserves_error_and_raises(replay_db, monkeypatch):
    _, factory = replay_db
    _seed_dead_letter(factory, id="dlq-fail-1")

    def boom(topic, payload):
        raise ConnectionError("broker unreachable")

    monkeypatch.setattr("producer.publish_transaction_event", boom)

    with pytest.raises(ConnectionError, match="broker unreachable"):
        replay_dead_letter_job("dlq-fail-1", "tenant-a")

    with factory() as session:
        row = session.query(DeadLetter).filter_by(id="dlq-fail-1").one()
        assert row.replay_status == "failed"
        assert "broker unreachable" in (row.error_detail or "")
        assert row.processed is not True
