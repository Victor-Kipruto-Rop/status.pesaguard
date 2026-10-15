from __future__ import annotations

from datetime import datetime, timezone
from threading import Event, Thread
import time
from types import ModuleType, SimpleNamespace

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import scheduler
import scheduler_worker
from models import Base, ScheduledJobRun


class _AvailableLock:
    def acquire(self, name, ttl_seconds=30.0):
        return f"token-{name}"

    def release(self, name, token):
        return True


def test_scheduler_worker_runs_due_jobs_with_each_trusted_tenant_context(
    monkeypatch,
):
    monkeypatch.delenv("PESAGUARD_ENVIRONMENT", raising=False)
    monkeypatch.setattr(
        scheduler,
        "get_distributed_lock",
        lambda: _AvailableLock(),
    )
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    scheduled_for = datetime(2026, 10, 1, tzinfo=timezone.utc)
    seen_tenants = []

    def handler(payload, context):
        seen_tenants.append(context.tenant_id)

    monkeypatch.setattr(scheduler_worker, "TASK_HANDLERS", {"test.task": handler})
    try:
        with factory() as session:
            for tenant_id in ("tenant-a", "tenant-b"):
                scheduler.Scheduler(session).register(
                    tenant_id,
                    "daily",
                    "test.task",
                    3600,
                    first_run_at=scheduled_for,
                )
            session.commit()

        outcomes = scheduler_worker.run_scheduler_tick(factory, engine)

        assert {result["tenant_id"] for result in outcomes} == {
            "tenant-a",
            "tenant-b",
        }
        assert [result["status"] for result in outcomes] == ["ran", "ran"]
        assert sorted(seen_tenants) == ["tenant-a", "tenant-b"]
        with factory() as session:
            runs = session.query(ScheduledJobRun).all()
            assert {run.tenant_id for run in runs} == {"tenant-a", "tenant-b"}
            assert all(run.status == "succeeded" for run in runs)
    finally:
        engine.dispose()


def test_worker_heartbeat_advances_while_scheduler_tick_is_running(
    monkeypatch,
    tmp_path,
):
    stop = Event()
    heartbeat = tmp_path / "scheduler.heartbeat"
    monkeypatch.setattr(scheduler_worker, "_stop", stop)
    thread = Thread(
        target=scheduler_worker._heartbeat_loop,
        args=(heartbeat, 0.01),
        daemon=True,
    )
    thread.start()
    try:
        deadline = time.monotonic() + 1
        while not heartbeat.exists() and time.monotonic() < deadline:
            time.sleep(0.005)
        assert heartbeat.exists()
        first_mtime = heartbeat.stat().st_mtime_ns
        time.sleep(0.03)
        assert heartbeat.stat().st_mtime_ns > first_mtime
    finally:
        stop.set()
        thread.join(timeout=1)
    assert not thread.is_alive()


def test_postgres_worker_tenant_enumeration_uses_registry_module(monkeypatch):
    registry = ModuleType("tenant_registry")
    registry.list_active_tenant_ids = lambda: ["tenant-a", "tenant-b"]
    monkeypatch.setitem(__import__("sys").modules, "tenant_registry", registry)
    engine = SimpleNamespace(dialect=SimpleNamespace(name="postgresql"))

    assert scheduler_worker._active_tenants(engine, None) == [
        "tenant-a",
        "tenant-b",
    ]


def test_scheduler_tick_logs_and_reports_tenant_errors(monkeypatch):
    captured = []
    monkeypatch.setattr(
        scheduler_worker,
        "_active_tenants",
        lambda _engine, _factory: ["", "tenant-a"],
    )
    monkeypatch.setattr(
        scheduler_worker,
        "capture_exception",
        lambda exc, **details: captured.append((exc, details)),
    )

    def failing_factory():
        raise RuntimeError("database unavailable")

    assert scheduler_worker.run_scheduler_tick(failing_factory, object()) == []
    assert len(captured) == 1
    assert captured[0][1]["operation"] == "scheduler_tenant_tick"
    assert captured[0][1]["extra"] == {"tenant_id": "tenant-a"}


def test_terminal_failure_hook_sends_sanitized_sentry_context(monkeypatch):
    captured = []
    monkeypatch.setattr(
        scheduler_worker,
        "capture_exception",
        lambda exc, **details: captured.append((exc, details)),
    )

    scheduler_worker._on_failure(
        {
            "tenant_id": "tenant-a",
            "job_key": "nightly",
            "attempts": 3,
            "status": "failed",
            "error_type": "RuntimeError",
            "reason": "sensitive data is not sent to Sentry",
        }
    )

    assert len(captured) == 1
    assert captured[0][1]["operation"] == "scheduled_job_failure"
    assert captured[0][1]["extra"] == {
        "tenant_id": "tenant-a",
        "job_key": "nightly",
        "backfill_id": None,
        "attempts": 3,
        "status": "failed",
        "error_type": "RuntimeError",
    }


def test_scheduler_worker_main_runs_tick_and_closes_engine(monkeypatch, tmp_path):
    monkeypatch.setenv("SCHEDULER_POLL_SECONDS", "1")
    monkeypatch.setenv(
        "PESAGUARD_SCHEDULER_HEARTBEAT_FILE",
        str(tmp_path / "scheduler.heartbeat"),
    )
    stop = Event()
    monkeypatch.setattr(scheduler_worker, "_stop", stop)
    engine = create_engine("sqlite:///:memory:")
    disposed = []
    monkeypatch.setattr(engine, "dispose", lambda: disposed.append(True))
    monkeypatch.setattr(scheduler_worker, "required_env", lambda _name: "sqlite://")
    monkeypatch.setattr(
        scheduler_worker,
        "create_database_engine",
        lambda _url: engine,
    )
    monkeypatch.setattr(scheduler_worker.signal, "signal", lambda *_: None)

    def stop_after_tick(*_args):
        stop.set()
        return []

    monkeypatch.setattr(scheduler_worker, "run_scheduler_tick", stop_after_tick)

    scheduler_worker.main()

    assert disposed == [True]
    assert (tmp_path / "scheduler.heartbeat").exists()


def test_scheduler_worker_rejects_invalid_poll_interval(monkeypatch):
    monkeypatch.setenv("SCHEDULER_POLL_SECONDS", "0")
    with pytest.raises(ValueError, match="between 1 and 3600"):
        scheduler_worker.main()


def test_shutdown_signal_sets_stop_event(monkeypatch):
    stop = Event()
    monkeypatch.setattr(scheduler_worker, "_stop", stop)

    scheduler_worker._request_shutdown(None, None)

    assert stop.is_set()
