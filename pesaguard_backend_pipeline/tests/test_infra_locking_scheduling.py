"""Unit + integration tests for the distributed lock, service discovery, and
scheduler services (implements Core API items 81, 134, 149).
"""

import threading
import time
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from distributed_lock import DistributedLockService
from models import Base, ScheduledJob
from scheduler import Scheduler, SchedulerError, as_utc
from service_discovery import ServiceDiscovery


# --------------------------------------------------------------------------
# Distributed lock
# --------------------------------------------------------------------------

def test_lock_mutual_exclusion_and_release():
    lock = DistributedLockService()  # in-memory fallback (no REDIS_URL in tests)
    assert lock.is_distributed is False

    token_a = lock.acquire("resource", ttl_seconds=30)
    assert token_a is not None
    assert lock.acquire("resource", ttl_seconds=30) is None
    assert lock.is_locked("resource") is True

    assert lock.release("resource", "wrong-token") is False
    assert lock.is_locked("resource") is True
    assert lock.release("resource", token_a) is True
    assert lock.is_locked("resource") is False
    assert lock.acquire("resource", ttl_seconds=30) is not None


def test_lock_ownership_prevents_double_release_and_stale_extend():
    lock = DistributedLockService()
    token = lock.acquire("res", ttl_seconds=30)
    assert lock.release("res", token) is True
    assert lock.release("res", token) is False
    assert lock.extend("res", token, ttl_seconds=30) is False


def test_lock_extend_only_by_owner():
    lock = DistributedLockService()
    token = lock.acquire("res", ttl_seconds=30)
    assert lock.extend("res", "other", ttl_seconds=30) is False
    assert lock.extend("res", token, ttl_seconds=60) is True


def test_lock_context_manager_releases_on_exception():
    lock = DistributedLockService()
    with pytest.raises(RuntimeError):
        with lock.hold("res", ttl_seconds=30, wait_seconds=0.5):
            assert lock.is_locked("res") is True
            raise RuntimeError("boom")
    assert lock.is_locked("res") is False


def test_lock_context_manager_raises_when_unavailable():
    lock = DistributedLockService()
    token = lock.acquire("res", ttl_seconds=30)
    try:
        with pytest.raises(Exception):
            with lock.hold("res", ttl_seconds=30, wait_seconds=0.3):
                pass
    finally:
        lock.release("res", token)


def test_lock_expiry_frees_lock():
    lock = DistributedLockService()
    lock.acquire("res", ttl_seconds=0.2)
    assert lock.is_locked("res") is True
    time.sleep(0.3)
    assert lock.is_locked("res") is False
    assert lock.acquire("res", ttl_seconds=5) is not None


def test_lock_thread_safety_only_one_winner():
    lock = DistributedLockService()
    winners = []
    barrier = threading.Barrier(8)

    def worker():
        barrier.wait()
        tok = lock.acquire("contended", ttl_seconds=30)
        if tok is not None:
            winners.append(tok)

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(winners) == 1


# --------------------------------------------------------------------------
# Service discovery
# --------------------------------------------------------------------------

def test_discovery_register_heartbeat_discover_deregister():
    sd = ServiceDiscovery()
    assert sd.is_distributed is False
    iid = sd.register("core-api", {"host": "10.0.0.1"}, ttl=30)
    inst = sd.discover("core-api")
    assert inst is not None and inst["instance_id"] == iid
    assert inst["metadata"]["host"] == "10.0.0.1"
    assert sd.is_healthy("core-api") is True

    assert sd.heartbeat("core-api", iid, ttl=30) is True
    assert sd.deregister("core-api", iid) is True
    assert sd.discover("core-api") is None


def test_discovery_heartbeat_wrong_owner_rejected():
    sd = ServiceDiscovery()
    iid = sd.register("core-api", ttl=30)
    assert sd.heartbeat("core-api", "not-the-owner", ttl=30) is False


def test_discovery_expiry_prunes_instance():
    sd = ServiceDiscovery()
    sd.register("core-api", ttl=0.2)
    assert sd.discover("core-api") is not None
    time.sleep(0.3)
    assert sd.discover("core-api") is None
    assert sd.is_healthy("core-api") is False


# --------------------------------------------------------------------------
# Scheduler
# --------------------------------------------------------------------------

def _session(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'sched.db'}")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)()


def test_scheduler_register_is_idempotent(tmp_path):
    session = _session(tmp_path)
    sched = Scheduler(session, lock=DistributedLockService(), handlers={"noop": lambda p: None})
    a = sched.register("tenant-a", "daily", "noop", 3600)
    b = sched.register("tenant-a", "daily", "noop", 3600)
    assert a.id == b.id
    assert session.query(ScheduledJob).count() == 1


def test_scheduler_rejects_invalid_interval_and_tenant(tmp_path):
    session = _session(tmp_path)
    sched = Scheduler(session, lock=DistributedLockService())
    with pytest.raises(SchedulerError):
        sched.register("", "k", "noop", 60)
    with pytest.raises(SchedulerError):
        sched.register("tenant-a", "k", "noop", 0)


def test_scheduler_runs_due_job_once_and_advances(tmp_path):
    session = _session(tmp_path)
    calls = []
    sched = Scheduler(session, lock=DistributedLockService(),
                      handlers={"task": lambda p: calls.append(p)})
    now = datetime(2026, 1, 1, tzinfo=timezone.utc)
    sched.register("tenant-a", "job-1", "task", 60,
                   first_run_at=now - timedelta(seconds=5), payload={"x": 1})

    outcomes = sched.run_due(now=now)
    assert len(outcomes) == 1
    assert outcomes[0]["status"] == "ran"
    assert len(calls) == 1 and calls[0] == {"x": 1}

    job = session.query(ScheduledJob).filter_by(job_key="job-1").one()
    assert job.run_count == 1
    # SQLite drops tzinfo on round-trip; normalize before comparing.
    assert as_utc(job.next_run_at) > now
    assert sched.run_due(now=now) == []


def test_scheduler_unknown_task_records_failure_and_advances(tmp_path):
    session = _session(tmp_path)
    sched = Scheduler(session, lock=DistributedLockService())  # no handlers
    now = datetime(2026, 1, 1, tzinfo=timezone.utc)
    sched.register("tenant-a", "job-x", "missing-task", 60,
                   first_run_at=now - timedelta(seconds=1))
    outcomes = sched.run_due(now=now)
    assert outcomes[0]["status"] == "failed"
    job = session.query(ScheduledJob).filter_by(job_key="job-x").one()
    assert job.failure_count == 1
    assert job.last_error is not None
    assert as_utc(job.next_run_at) > now


def test_scheduler_disabled_job_is_not_run(tmp_path):
    session = _session(tmp_path)
    sched = Scheduler(session, lock=DistributedLockService(),
                      handlers={"task": lambda p: None})
    now = datetime(2026, 1, 1, tzinfo=timezone.utc)
    sched.register("tenant-a", "job-d", "task", 60,
                   first_run_at=now - timedelta(seconds=1))
    sched.set_enabled("tenant-a", "job-d", False)
    assert sched.run_due(now=now) == []


def test_scheduler_second_worker_skips_locked(tmp_path):
    session = _session(tmp_path)
    lock = DistributedLockService()
    sched = Scheduler(session, lock=lock, handlers={"task": lambda p: None})
    now = datetime(2026, 1, 1, tzinfo=timezone.utc)
    sched.register("tenant-a", "job-lock", "task", 60,
                   first_run_at=now - timedelta(seconds=1))
    job_id = session.query(ScheduledJob).one().id
    token = lock.acquire(f"scheduler:{job_id}", ttl_seconds=30)
    try:
        outcomes = sched.run_due(now=now)
        assert outcomes[0]["status"] == "skipped_locked"
    finally:
        lock.release(f"scheduler:{job_id}", token)

