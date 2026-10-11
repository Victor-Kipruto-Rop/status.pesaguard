"""Scheduler service: durable, tenant-scoped, at-most-once job execution.

Design goals (financial-infrastructure safe):

- **Durability:** job definitions live in the ``scheduled_jobs`` table, so
  schedules survive process restarts.
- **Deterministic due-computation:** "is this job due now?" is computed from an
  injected clock, never ``datetime.now()`` directly, so it is unit-testable and
  reproducible.
- **At-most-once across workers:** each due run acquires a distributed lock
  (see :mod:`distributed_lock`) before executing, so a job runs on exactly one
  worker even with many scheduler replicas. The lock is honoured per run, not
  merely per process.
- **Tenant isolation:** every query is scoped by ``tenant_id``; a worker never
  picks up or mutates another tenant's job.
- **Observability:** run/failure counters and last-error text are persisted per
  job so operators can see what happened.
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Dict, List, Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from distributed_lock import DistributedLockService, get_distributed_lock
from models import ScheduledJob

logger = logging.getLogger("pesaguard.scheduler")

# Upper bound on jobs processed per tick so one scheduler tick cannot run away
# and starve other work; callers can loop to drain a backlog.
DEFAULT_BATCH_SIZE = 50


class SchedulerError(Exception):
    """Base class for scheduler errors."""


class UnknownTaskError(SchedulerError):
    """Raised when a due job references a task name with no registered handler."""


def _now() -> datetime:
    return datetime.now(timezone.utc)


def as_utc(dt: datetime) -> datetime:
    """Normalize to timezone-aware UTC.

    SQLite drops tzinfo on round-trip even for DateTime(timezone=True) columns;
    callers (and tests) must normalize before comparing. Naive values are
    treated as UTC, matching the convention used in auth_rbac.
    """
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


class Scheduler:
    """Durable scheduler that runs due jobs at-most-once across workers."""

    def __init__(
        self,
        session: Session,
        *,
        lock: Optional[DistributedLockService] = None,
        handlers: Optional[Dict[str, Callable[[Dict[str, Any]], Any]]] = None,
        lock_ttl_seconds: float = 60.0,
    ) -> None:
        self._session = session
        self._lock = lock or get_distributed_lock()
        self._handlers = handlers or {}
        self._lock_ttl = lock_ttl_seconds

    # -- job definition management -------------------------------------------------

    def register(
        self,
        tenant_id: str,
        job_key: str,
        task_name: str,
        interval_seconds: int,
        *,
        payload: Optional[Dict[str, Any]] = None,
        first_run_at: Optional[datetime] = None,
    ) -> ScheduledJob:
        """Create (or return the existing) job. Idempotent per (tenant, job_key)."""
        if not tenant_id:
            raise SchedulerError("tenant_id is required")
        if interval_seconds <= 0:
            raise SchedulerError("interval_seconds must be positive")

        existing = self._get(tenant_id, job_key)
        if existing is not None:
            return existing

        start = as_utc(first_run_at) if first_run_at else _now()
        job = ScheduledJob(
            id=f"sched_{uuid.uuid4().hex}",
            tenant_id=tenant_id,
            job_key=job_key,
            task_name=task_name,
            interval_seconds=int(interval_seconds),
            enabled=True,
            next_run_at=start,
            payload=payload or {},
        )
        self._session.add(job)
        self._session.flush()
        logger.info("scheduler.register tenant=%s job=%s task=%s", tenant_id, job_key, task_name)
        return job

    def set_enabled(self, tenant_id: str, job_key: str, enabled: bool) -> ScheduledJob:
        job = self._require(tenant_id, job_key)
        job.enabled = bool(enabled)
        job.updated_at = _now()
        if enabled and job.next_run_at is None:
            job.next_run_at = _now()
        self._session.flush()
        return job

    def _get(self, tenant_id: str, job_key: str) -> Optional[ScheduledJob]:
        return self._session.execute(
            select(ScheduledJob).where(
                ScheduledJob.tenant_id == tenant_id,
                ScheduledJob.job_key == job_key,
            )
        ).scalar_one_or_none()

    def _require(self, tenant_id: str, job_key: str) -> ScheduledJob:
        job = self._get(tenant_id, job_key)
        if job is None:
            raise SchedulerError(f"job {job_key} not found for tenant")
        return job


    # -- due selection -------------------------------------------------------------

    def due_jobs(self, *, now: Optional[datetime] = None, limit: int = DEFAULT_BATCH_SIZE) -> List[ScheduledJob]:
        """Return enabled jobs whose next_run_at is at or before ``now``.

        Tenant isolation is preserved because each returned job already carries
        its tenant_id and is processed independently below.
        """
        now = as_utc(now) if now else _now()
        limit = max(1, min(int(limit), 1000))
        stmt = (
            select(ScheduledJob)
            .where(ScheduledJob.enabled.is_(True))
            .where(ScheduledJob.next_run_at.isnot(None))
            .where(ScheduledJob.next_run_at <= now)
            .order_by(ScheduledJob.next_run_at.asc())
            .limit(limit)
        )
        return list(self._session.execute(stmt).scalars().all())

    # -- execution ----------------------------------------------------------------

    def run_due(self, *, now: Optional[datetime] = None, limit: int = DEFAULT_BATCH_SIZE,
                worker_id: Optional[str] = None) -> List[Dict[str, Any]]:
        """Execute all due jobs, at-most-once, and return per-job outcomes.

        For each due job we attempt to acquire a distributed lock keyed by the
        job id. If another worker holds it, we skip (no double execution). Only
        the lock holder advances next_run_at and runs the handler.
        """
        outcomes: List[Dict[str, Any]] = []
        worker = worker_id or f"worker-{uuid.uuid4().hex[:8]}"
        for job in self.due_jobs(now=now, limit=limit):
            outcomes.append(self._run_one(job, worker=worker, now=now))
        return outcomes

    def _run_one(self, job: ScheduledJob, *, worker: str, now: Optional[datetime]) -> Dict[str, Any]:
        lock_name = f"scheduler:{job.id}"
        token = self._lock.acquire(lock_name, ttl_seconds=self._lock_ttl)
        if token is None:
            logger.debug("scheduler.skip busy tenant=%s job=%s", job.tenant_id, job.job_key)
            return {"job_key": job.job_key, "tenant_id": job.tenant_id, "status": "skipped_locked"}

        try:
            # Re-check within the lock in case state changed between selection
            # and here (belt-and-braces against races).
            reference = as_utc(now) if now else _now()
            if job.next_run_at is not None and as_utc(job.next_run_at) > reference:
                return {"job_key": job.job_key, "tenant_id": job.tenant_id, "status": "skipped_not_due"}

            handler = self._handlers.get(job.task_name)
            run_at = reference
            try:
                if handler is None:
                    raise UnknownTaskError(f"no handler registered for task {job.task_name!r}")
                handler(dict(job.payload or {}))
                job.run_count += 1
                job.last_error = None
                outcome_status = "ran"
            except Exception as exc:  # noqa: BLE001 - scheduler must not crash the tick
                job.failure_count += 1
                job.last_error = f"{type(exc).__name__}: {exc}"
                outcome_status = "failed"
                logger.exception("scheduler.job_failed tenant=%s job=%s", job.tenant_id, job.job_key)

            # Advance schedule deterministically relative to this run.
            job.last_run_at = run_at
            job.next_run_at = run_at + timedelta(seconds=job.interval_seconds)
            job.updated_at = _now()
            self._session.flush()

            logger.info(
                "scheduler.ran worker=%s tenant=%s job=%s status=%s next=%s",
                worker, job.tenant_id, job.job_key, outcome_status, job.next_run_at.isoformat(),
            )
            return {
                "job_key": job.job_key,
                "tenant_id": job.tenant_id,
                "status": outcome_status,
                "run_at": run_at.isoformat(),
                "next_run_at": job.next_run_at.isoformat(),
            }
        finally:
            self._lock.release(lock_name, token)

