"""Long-running tenant-scoped scheduler worker entry point."""

from __future__ import annotations

import logging
import os
import signal
import threading
import time
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import sessionmaker

from database_service import create_database_engine
from environment import required_env
from models import ScheduledJob
from observability import capture_exception
from scheduler import Scheduler, SchedulerError
from scheduled_tasks import TASK_HANDLERS
from tenant_context import tenant_scope

logger = logging.getLogger("pesaguard.scheduler_worker")
_stop = threading.Event()


def _on_failure(details: dict) -> None:
    safe_details = {
        key: details.get(key)
        for key in (
            "tenant_id",
            "job_key",
            "backfill_id",
            "attempts",
            "status",
            "error_type",
        )
    }
    logger.critical("scheduler.operator_alert details=%s", safe_details)
    capture_exception(
        SchedulerError(
            f"scheduled work reached terminal state {details.get('status')}"
        ),
        operation="scheduled_job_failure",
        extra=safe_details,
    )


def _active_tenants(engine, session_factory) -> list[str]:
    if engine.dialect.name == "postgresql":
        from tenant_registry import list_active_tenant_ids

        return list_active_tenant_ids()
    with session_factory() as session:
        rows = session.execute(
            select(ScheduledJob.tenant_id).distinct().order_by(ScheduledJob.tenant_id)
        ).scalars()
        return list(rows)


def run_scheduler_tick(session_factory, engine) -> list[dict]:
    """Run one bounded scheduler tick for each trusted active tenant."""
    outcomes: list[dict] = []
    tenants = _active_tenants(engine, session_factory)
    for tenant_id in tenants:
        if not tenant_id:
            continue
        try:
            with tenant_scope(tenant_id):
                with session_factory() as session:
                    scheduler = Scheduler(
                        session,
                        handlers=TASK_HANDLERS,
                        on_failure=_on_failure,
                    )
                    outcomes.extend(
                        scheduler.run_due(
                            tenant_id=tenant_id,
                            worker_id=f"scheduler-{os.getpid()}",
                        )
                    )
        except Exception as exc:
            logger.exception(
                "scheduler.tenant_tick_failed tenant=%s",
                tenant_id,
            )
            capture_exception(
                exc,
                operation="scheduler_tenant_tick",
                extra={"tenant_id": tenant_id},
            )
    return outcomes


def _request_shutdown(_signum, _frame) -> None:
    _stop.set()


def _heartbeat_loop(path: Path, interval_seconds: float) -> None:
    while not _stop.is_set():
        try:
            path.touch()
        except OSError:
            logger.exception("scheduler.heartbeat_write_failed path=%s", path)
        _stop.wait(interval_seconds)


def main() -> None:
    poll_seconds = int(os.getenv("SCHEDULER_POLL_SECONDS", "15"))
    if not 1 <= poll_seconds <= 3600:
        raise ValueError("SCHEDULER_POLL_SECONDS must be between 1 and 3600")

    database_url = required_env("DATABASE_URL")
    engine = create_database_engine(database_url)
    session_factory = sessionmaker(bind=engine, expire_on_commit=False)
    signal.signal(signal.SIGTERM, _request_shutdown)
    signal.signal(signal.SIGINT, _request_shutdown)
    logger.info("scheduler.worker_started poll_seconds=%s", poll_seconds)
    heartbeat_path = Path(
        os.getenv(
            "PESAGUARD_SCHEDULER_HEARTBEAT_FILE",
            "/tmp/pesaguard-scheduler.heartbeat",
        )
    )
    heartbeat_thread = threading.Thread(
        target=_heartbeat_loop,
        args=(heartbeat_path, max(1.0, min(float(poll_seconds), 30.0))),
        name="scheduler-heartbeat",
        daemon=True,
    )
    heartbeat_thread.start()
    try:
        while not _stop.is_set():
            started = time.monotonic()
            run_scheduler_tick(session_factory, engine)
            elapsed = time.monotonic() - started
            _stop.wait(max(0.0, poll_seconds - elapsed))
    finally:
        _stop.set()
        heartbeat_thread.join(timeout=2.0)
        engine.dispose()
        logger.info("scheduler.worker_stopped")


if __name__ == "__main__":
    main()
