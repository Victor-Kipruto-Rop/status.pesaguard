"""Scheduler service: durable tenant-scoped scheduling with bounded retries.

Design goals (financial-infrastructure safe):

- **Durability:** job definitions live in the ``scheduled_jobs`` table, so
  schedules survive process restarts.
- **Deterministic due-computation:** "is this job due now?" is computed from an
  injected clock, never ``datetime.now()`` directly, so it is unit-testable and
  reproducible.
- **Concurrent-run exclusion:** each due run acquires a distributed lock
  (see :mod:`distributed_lock`) before executing. Retried runs are at-least-once;
  task handlers must be idempotent.
- **Tenant isolation:** tenant-owned reads and writes are filtered by
  ``tenant_id`` and protected by PostgreSQL row-level security.
- **Observability:** each attempt is committed before task execution and
  persisted in tenant-scoped run history.
"""

from __future__ import annotations

import logging
import multiprocessing
import inspect
import os
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Dict, List, Optional

from sqlalchemy import and_, exists, func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from distributed_lock import DistributedLockService, get_distributed_lock
from models import (
    ScheduledJob,
    ScheduledJobBackfill,
    ScheduledJobDependency,
    ScheduledJobRun,
)
from tenant_context import tenant_scope

logger = logging.getLogger("pesaguard.scheduler")

# Upper bound on jobs processed per tick so one scheduler tick cannot run away
# and starve other work; callers can loop to drain a backlog.
DEFAULT_BATCH_SIZE = 50
MAX_ATTEMPTS = 5
MAX_RETRY_BACKOFF_SECONDS = 300
MAX_RUN_HISTORY_LIMIT = 500
MAX_BACKFILL_WINDOW = timedelta(days=366)


class SchedulerError(Exception):
    """Base class for scheduler errors."""


class UnknownTaskError(SchedulerError):
    """Raised when a due job references a task name with no registered handler."""


class SchedulerTimeoutError(SchedulerError):
    """Raised when an isolated scheduled task exceeds its execution deadline."""


class RemoteScheduledTaskError(SchedulerError):
    """A task raised in the isolated worker process."""

    def __init__(self, error_type: str) -> None:
        self.remote_error_type = error_type
        super().__init__(error_type)


@dataclass(frozen=True)
class ScheduledTaskContext:
    """Stable execution metadata passed to two-argument task handlers."""

    tenant_id: str
    job_key: str
    run_id: str
    scheduled_for: datetime
    attempt_number: int
    idempotency_key: str
    trigger_type: str = "scheduled"
    window_start: Optional[datetime] = None
    window_end: Optional[datetime] = None


def _invoke_in_child(
    handler: Callable[..., Any],
    payload: Dict[str, Any],
    context: ScheduledTaskContext,
    tenant_id: str,
    connection: Any,
) -> None:
    """Run an importable handler with a fresh tenant scope and report its type."""
    try:
        with tenant_scope(tenant_id):
            _invoke_handler(handler, payload, context)
        connection.send((True, None))
    except Exception as exc:
        connection.send((False, type(exc).__name__))
    finally:
        connection.close()


def _invoke_handler(
    handler: Callable[..., Any],
    payload: Dict[str, Any],
    context: ScheduledTaskContext,
) -> Any:
    try:
        signature = inspect.signature(handler)
    except (TypeError, ValueError) as exc:
        raise SchedulerError("scheduled task handler signature is not inspectable") from exc
    parameters = signature.parameters.values()
    accepts_context = any(
        parameter.kind == inspect.Parameter.VAR_POSITIONAL
        for parameter in parameters
    ) or len(signature.parameters) >= 2
    if accepts_context:
        return handler(payload, context)
    return handler(payload)


def _handler_accepts_context(handler: Callable[..., Any]) -> bool:
    try:
        signature = inspect.signature(handler)
    except (TypeError, ValueError) as exc:
        raise SchedulerError("scheduled task handler signature is not inspectable") from exc
    return len(signature.parameters) >= 2 or any(
        parameter.kind == inspect.Parameter.VAR_POSITIONAL
        for parameter in signature.parameters.values()
    )

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
    """Durable scheduler with lock-protected, retryable scheduled attempts."""

    def __init__(
        self,
        session: Session,
        *,
        lock: Optional[DistributedLockService] = None,
        handlers: Optional[Dict[str, Callable[[Dict[str, Any]], Any]]] = None,
        lock_ttl_seconds: float = 60.0,
        on_failure: Optional[Callable[[Dict[str, Any]], Any]] = None,
    ) -> None:
        self._session = session
        self._lock = lock or get_distributed_lock()
        self._handlers = handlers or {}
        self._lock_ttl = lock_ttl_seconds
        self._on_failure = on_failure

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
        max_attempts: int = 3,
        retry_backoff_seconds: int = 30,
        timeout_seconds: Optional[int] = None,
    ) -> ScheduledJob:
        """Create (or return the existing) job. Idempotent per (tenant, job_key)."""
        if not tenant_id:
            raise SchedulerError("tenant_id is required")
        if not isinstance(job_key, str) or not job_key.strip() or len(job_key) > 255:
            raise SchedulerError("job_key must contain 1 to 255 characters")
        if not isinstance(task_name, str) or not task_name.strip() or len(task_name) > 255:
            raise SchedulerError("task_name must contain 1 to 255 characters")
        if type(interval_seconds) is not int or interval_seconds <= 0:
            raise SchedulerError("interval_seconds must be a positive integer")
        if payload is not None and not isinstance(payload, dict):
            raise SchedulerError("payload must be an object")
        if type(max_attempts) is not int or not 1 <= max_attempts <= MAX_ATTEMPTS:
            raise SchedulerError(f"max_attempts must be between 1 and {MAX_ATTEMPTS}")
        if (
            type(retry_backoff_seconds) is not int
            or not 1 <= retry_backoff_seconds <= 3600
        ):
            raise SchedulerError("retry_backoff_seconds must be between 1 and 3600")
        if timeout_seconds is not None and (
            type(timeout_seconds) is not int or not 1 <= timeout_seconds <= 3600
        ):
            raise SchedulerError("timeout_seconds must be between 1 and 3600")

        payload_value = payload or {}

        def matches_definition(existing: ScheduledJob) -> bool:
            return (
                existing.task_name == task_name
                and existing.interval_seconds == interval_seconds
                and (existing.payload or {}) == payload_value
                and existing.max_attempts == max_attempts
                and existing.retry_backoff_seconds == retry_backoff_seconds
                and existing.timeout_seconds == timeout_seconds
            )

        existing = self._get(tenant_id, job_key)
        if existing is not None:
            if not matches_definition(existing):
                raise SchedulerError(
                    "job key is already registered with a different configuration"
                )
            return existing

        start = as_utc(first_run_at) if first_run_at else _now()
        job = ScheduledJob(
            id=f"sched_{uuid.uuid4().hex}",
            tenant_id=tenant_id,
            job_key=job_key,
            task_name=task_name,
            interval_seconds=interval_seconds,
            enabled=True,
            next_run_at=start,
            payload=payload_value,
            max_attempts=max_attempts,
            retry_backoff_seconds=retry_backoff_seconds,
            timeout_seconds=timeout_seconds,
        )
        try:
            with self._session.begin_nested():
                self._session.add(job)
                self._session.flush()
        except IntegrityError:
            concurrent = self._get(tenant_id, job_key)
            if concurrent is None:
                raise
            if not matches_definition(concurrent):
                raise SchedulerError(
                    "job key was concurrently registered with a different configuration"
                )
            return concurrent
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

    def add_dependency(
        self,
        tenant_id: str,
        job_key: str,
        depends_on_job_key: str,
    ) -> None:
        """Add a tenant-local DAG edge after validating schedule alignment and cycles."""
        job = self._require(tenant_id, job_key)
        parent = self._require(tenant_id, depends_on_job_key)
        if job.id == parent.id:
            raise SchedulerError("a job cannot depend on itself")
        if (
            job.interval_seconds != parent.interval_seconds
            or (as_utc(job.next_run_at) if job.next_run_at else None)
            != (as_utc(parent.next_run_at) if parent.next_run_at else None)
        ):
            raise SchedulerError(
                "dependent jobs must have the same interval and next scheduled time"
            )

        edges = self._dependency_edges(tenant_id)
        pending = [parent.id]
        visited = set()
        while pending:
            current = pending.pop()
            if current == job.id:
                raise SchedulerError("dependency would create a cycle")
            if current in visited:
                continue
            visited.add(current)
            pending.extend(edges.get(current, set()))

        existing = self._session.get(
            ScheduledJobDependency,
            (tenant_id, job.id, parent.id),
        )
        if existing is None:
            self._session.add(
                ScheduledJobDependency(
                    tenant_id=tenant_id,
                    job_id=job.id,
                    depends_on_job_id=parent.id,
                )
            )
            self._session.flush()

    def dependencies(self, tenant_id: str, job_key: str) -> List[str]:
        """Return prerequisite job keys for one tenant's scheduled job."""
        job = self._require(tenant_id, job_key)
        rows = self._session.execute(
            select(ScheduledJob.job_key)
            .join(
                ScheduledJobDependency,
                and_(
                    ScheduledJobDependency.depends_on_job_id == ScheduledJob.id,
                    ScheduledJobDependency.tenant_id == ScheduledJob.tenant_id,
                ),
            )
            .where(
                ScheduledJobDependency.tenant_id == tenant_id,
                ScheduledJobDependency.job_id == job.id,
            )
            .order_by(ScheduledJob.job_key)
        ).scalars()
        return list(rows)

    def _dependency_edges(self, tenant_id: str) -> Dict[str, set[str]]:
        rows = self._session.execute(
            select(
                ScheduledJobDependency.job_id,
                ScheduledJobDependency.depends_on_job_id,
            ).where(ScheduledJobDependency.tenant_id == tenant_id)
        ).all()
        edges: Dict[str, set[str]] = {}
        for child_id, parent_id in rows:
            edges.setdefault(child_id, set()).add(parent_id)
        return edges

    def enqueue_backfill(
        self,
        tenant_id: str,
        job_key: str,
        start_at: datetime,
        end_at: datetime,
        *,
        idempotency_key: str,
        requested_by: str,
        reason: str,
        max_attempts: Optional[int] = None,
    ) -> ScheduledJobBackfill:
        """Persist an idempotent, bounded historical execution request."""
        job = self._require(tenant_id, job_key)
        start = as_utc(start_at)
        end = as_utc(end_at)
        if end <= start or end - start > MAX_BACKFILL_WINDOW:
            raise SchedulerError("backfill window must be positive and no longer than 366 days")
        if (
            not isinstance(idempotency_key, str)
            or not 8 <= len(idempotency_key.strip()) <= 255
        ):
            raise SchedulerError("idempotency_key must contain 8 to 255 characters")
        if not isinstance(requested_by, str) or not requested_by.strip() or len(requested_by) > 255:
            raise SchedulerError("requested_by must contain 1 to 255 characters")
        if not isinstance(reason, str) or not reason.strip() or len(reason) > 2000:
            raise SchedulerError("reason must contain 1 to 2000 characters")
        attempts = job.max_attempts if max_attempts is None else max_attempts
        if type(attempts) is not int or not 1 <= attempts <= job.max_attempts:
            raise SchedulerError("backfill max_attempts must be within the job retry policy")

        existing = self._session.execute(
            select(ScheduledJobBackfill).where(
                ScheduledJobBackfill.tenant_id == tenant_id,
                ScheduledJobBackfill.job_id == job.id,
                ScheduledJobBackfill.idempotency_key == idempotency_key.strip(),
            )
        ).scalar_one_or_none()
        if existing is not None:
            if (
                as_utc(existing.start_at) != start
                or as_utc(existing.end_at) != end
                or existing.requested_by != requested_by.strip()
                or existing.reason != reason.strip()
            ):
                raise SchedulerError("idempotency key was already used for a different request")
            return existing

        request = ScheduledJobBackfill(
            tenant_id=tenant_id,
            job_id=job.id,
            job_key=job.job_key,
            start_at=start,
            end_at=end,
            idempotency_key=idempotency_key.strip(),
            status="queued",
            attempt_count=0,
            max_attempts=attempts,
            requested_by=requested_by.strip(),
            reason=reason.strip(),
        )
        try:
            with self._session.begin_nested():
                self._session.add(request)
                self._session.flush()
        except IntegrityError:
            concurrent = self._session.execute(
                select(ScheduledJobBackfill).where(
                    ScheduledJobBackfill.tenant_id == tenant_id,
                    ScheduledJobBackfill.job_id == job.id,
                    ScheduledJobBackfill.idempotency_key == idempotency_key.strip(),
                )
            ).scalar_one_or_none()
            if concurrent is None or (
                as_utc(concurrent.start_at) != start
                or as_utc(concurrent.end_at) != end
                or concurrent.requested_by != requested_by.strip()
                or concurrent.reason != reason.strip()
            ):
                raise SchedulerError(
                    "idempotency key was concurrently used for a different request"
                )
            return concurrent
        return request


    # -- due selection -------------------------------------------------------------

    def due_jobs(
        self,
        *,
        now: Optional[datetime] = None,
        limit: int = DEFAULT_BATCH_SIZE,
        tenant_id: Optional[str] = None,
    ) -> List[ScheduledJob]:
        """Return enabled jobs whose schedule or retry time is due.

        Supply ``tenant_id`` to bound the selection to one tenant. Production
        PostgreSQL calls must also establish that tenant's RLS context.
        """
        now = as_utc(now) if now else _now()
        limit = max(1, min(int(limit), 1000))
        stmt = (
            select(ScheduledJob)
            .where(ScheduledJob.enabled.is_(True))
            .where(
                or_(
                    and_(
                        ScheduledJob.retry_at.isnot(None),
                        ScheduledJob.retry_at <= now,
                    ),
                    and_(
                        ScheduledJob.retry_at.is_(None),
                        ScheduledJob.next_run_at.isnot(None),
                        ScheduledJob.next_run_at <= now,
                    ),
                    exists(
                        select(1).where(
                            ScheduledJobBackfill.tenant_id == ScheduledJob.tenant_id,
                            ScheduledJobBackfill.job_id == ScheduledJob.id,
                            or_(
                                ScheduledJobBackfill.status == "queued",
                                and_(
                                    ScheduledJobBackfill.status == "retrying",
                                    or_(
                                        ScheduledJobBackfill.retry_at.is_(None),
                                        ScheduledJobBackfill.retry_at <= now,
                                    ),
                                ),
                                ScheduledJobBackfill.status == "running",
                            ),
                        )
                    ),
                )
            )
            .order_by(func.coalesce(ScheduledJob.retry_at, ScheduledJob.next_run_at).asc())
            .limit(limit)
        )
        if tenant_id is not None:
            if not tenant_id.strip():
                raise SchedulerError("tenant_id cannot be empty")
            stmt = stmt.where(ScheduledJob.tenant_id == tenant_id)
        return list(self._session.execute(stmt).scalars().all())

    def run_history(
        self,
        tenant_id: str,
        job_key: str,
        *,
        limit: int = 100,
    ) -> List[ScheduledJobRun]:
        """Return recent attempt records for one tenant's scheduled job."""
        if not tenant_id:
            raise SchedulerError("tenant_id is required")
        if type(limit) is not int or not 1 <= limit <= MAX_RUN_HISTORY_LIMIT:
            raise SchedulerError(
                f"run history limit must be between 1 and {MAX_RUN_HISTORY_LIMIT}"
            )
        stmt = (
            select(ScheduledJobRun)
            .where(
                ScheduledJobRun.tenant_id == tenant_id,
                ScheduledJobRun.job_key == job_key,
            )
            .order_by(ScheduledJobRun.started_at.desc())
            .limit(limit)
        )
        return list(self._session.execute(stmt).scalars().all())

    def resolve_interrupted_run(
        self,
        tenant_id: str,
        run_id: str,
        *,
        resolution: str,
        operator_id: str,
        reason: str,
        confirm_idempotent_replay: bool = False,
        now: Optional[datetime] = None,
    ) -> Dict[str, Any]:
        """Explicitly skip or authorize replay of one interrupted attempt."""
        if not tenant_id:
            raise SchedulerError("tenant_id is required")
        if resolution not in {"retry", "skip"}:
            raise SchedulerError("resolution must be 'retry' or 'skip'")
        if not isinstance(operator_id, str) or not operator_id.strip() or len(operator_id) > 255:
            raise SchedulerError("operator_id must contain 1 to 255 characters")
        if not isinstance(reason, str) or not reason.strip() or len(reason) > 2000:
            raise SchedulerError("reason must contain 1 to 2000 characters")
        if resolution == "retry" and confirm_idempotent_replay is not True:
            raise SchedulerError(
                "retry requires explicit idempotent-replay confirmation"
            )

        run = self._session.execute(
            select(ScheduledJobRun).where(
                ScheduledJobRun.id == run_id,
                ScheduledJobRun.tenant_id == tenant_id,
                ScheduledJobRun.status == "interrupted",
            )
        ).scalar_one_or_none()
        if run is None:
            raise SchedulerError("interrupted run not found for tenant")

        lock_name = f"scheduler:{run.job_id}"
        token = self._lock.acquire(lock_name, ttl_seconds=self._lock_ttl)
        if token is None:
            raise SchedulerError("job is currently locked; retry the resolution later")
        try:
            run = self._session.execute(
                select(ScheduledJobRun).where(
                    ScheduledJobRun.id == run_id,
                    ScheduledJobRun.tenant_id == tenant_id,
                    ScheduledJobRun.status == "interrupted",
                )
            ).scalar_one_or_none()
            if run is None:
                raise SchedulerError("interrupted run has already been resolved")
            job = self._session.execute(
                select(ScheduledJob).where(
                    ScheduledJob.id == run.job_id,
                    ScheduledJob.tenant_id == tenant_id,
                )
            ).scalar_one_or_none()
            if job is None:
                raise SchedulerError("scheduled job not found for interrupted run")

            reference = as_utc(now) if now else _now()
            backfill = None
            if run.trigger_type == "backfill":
                backfill = self._session.execute(
                    select(ScheduledJobBackfill).where(
                        ScheduledJobBackfill.id == run.backfill_id,
                        ScheduledJobBackfill.tenant_id == tenant_id,
                    )
                ).scalar_one_or_none()
                if backfill is None or backfill.status != "interrupted":
                    raise SchedulerError("interrupted backfill not found for tenant")
            if resolution == "retry":
                retry_limit = backfill.max_attempts if backfill is not None else job.max_attempts
                if run.attempt_number >= retry_limit:
                    raise SchedulerError("retry limit has already been exhausted")
                run.error_type = "OperatorAuthorizedReplay"
                if backfill is not None:
                    backfill.status = "retrying"
                    backfill.retry_at = reference
                    backfill.error_type = "OperatorAuthorizedReplay"
                    backfill.updated_at = reference
                else:
                    job.retry_at = reference
                outcome = "retry_scheduled"
            else:
                run.error_type = "OperatorSkippedInterruptedRun"
                if backfill is not None:
                    backfill.status = "failed"
                    backfill.retry_at = None
                    backfill.error_type = "OperatorSkippedInterruptedRun"
                    backfill.updated_at = reference
                else:
                    job.retry_at = None
                    job.last_run_at = reference
                    job.next_run_at = reference + timedelta(seconds=job.interval_seconds)
                outcome = "skipped"

            run.status = "failed"
            run.finished_at = run.finished_at or reference
            run.resolution_by = operator_id.strip()
            run.resolution_reason = reason.strip()
            if backfill is None:
                job.enabled = True
            job.updated_at = reference
            job_key = run.job_key
            self._session.commit()
            logger.warning(
                "scheduler.interrupted_run_resolved "
                "tenant=%s job=%s run=%s resolution=%s operator=%s",
                tenant_id,
                job_key,
                run_id,
                resolution,
                operator_id.strip(),
            )
            return {
                "run_id": run_id,
                "tenant_id": tenant_id,
                "job_key": job_key,
                "resolution": outcome,
            }
        finally:
            self._lock.release(lock_name, token)

    # -- execution ----------------------------------------------------------------

    def run_due(
        self,
        *,
        now: Optional[datetime] = None,
        limit: int = DEFAULT_BATCH_SIZE,
        worker_id: Optional[str] = None,
        tenant_id: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """Execute due jobs and return per-attempt outcomes.

        For each due job we attempt to acquire a distributed lock keyed by the
        job id. If another worker holds it, we skip. Failed handler calls retry
        with bounded exponential backoff; task side effects must be idempotent.
        """
        outcomes: List[Dict[str, Any]] = []
        worker = worker_id or f"worker-{uuid.uuid4().hex[:8]}"
        jobs = self.due_jobs(now=now, limit=limit, tenant_id=tenant_id)
        jobs = self._topological_order(jobs)
        for job in jobs:
            outcomes.append(self._run_one(job, worker=worker, now=now))
        return outcomes

    def _topological_order(self, jobs: List[ScheduledJob]) -> List[ScheduledJob]:
        by_id = {job.id: job for job in jobs}
        edges_by_tenant = {
            tenant: self._dependency_edges(tenant)
            for tenant in {job.tenant_id for job in jobs}
        }
        depths: Dict[tuple[str, str], int] = {}

        def depth(job: ScheduledJob, visiting: set[tuple[str, str]]) -> int:
            identity = (job.tenant_id, job.id)
            if identity in depths:
                return depths[identity]
            if identity in visiting:
                raise SchedulerError("persisted job dependency graph contains a cycle")
            visiting.add(identity)
            result = 0
            for parent_id in edges_by_tenant[job.tenant_id].get(job.id, set()):
                if parent_id in by_id:
                    result = max(
                        result,
                        depth(by_id[parent_id], visiting) + 1,
                    )
            visiting.remove(identity)
            depths[identity] = result
            return result

        return sorted(
            jobs,
            key=lambda job: (
                depth(job, set()),
                as_utc(job.retry_at or job.next_run_at) if job.retry_at or job.next_run_at else _now(),
                job.job_key,
            ),
        )

    def _dependencies_satisfied(
        self,
        job: ScheduledJob,
        scheduled_for: datetime,
        *,
        backfill: Optional[ScheduledJobBackfill] = None,
    ) -> bool:
        dependency_ids = self._dependency_edges(job.tenant_id).get(job.id, set())
        for dependency_id in dependency_ids:
            if backfill is not None:
                parent_done = self._session.execute(
                    select(ScheduledJobBackfill.id).where(
                        ScheduledJobBackfill.tenant_id == job.tenant_id,
                        ScheduledJobBackfill.job_id == dependency_id,
                        ScheduledJobBackfill.idempotency_key == backfill.idempotency_key,
                        ScheduledJobBackfill.start_at == backfill.start_at,
                        ScheduledJobBackfill.end_at == backfill.end_at,
                        ScheduledJobBackfill.status == "succeeded",
                    )
                ).scalar_one_or_none()
            else:
                parent_done = self._session.execute(
                    select(ScheduledJobRun.id).where(
                        ScheduledJobRun.tenant_id == job.tenant_id,
                        ScheduledJobRun.job_id == dependency_id,
                        ScheduledJobRun.scheduled_for == scheduled_for,
                        ScheduledJobRun.status == "succeeded",
                        ScheduledJobRun.trigger_type == "scheduled",
                    )
                ).scalar_one_or_none()
            if parent_done is None:
                return False
        return True

    def _execute_handler(
        self,
        handler: Callable[..., Any],
        payload: Dict[str, Any],
        context: ScheduledTaskContext,
        *,
        timeout_seconds: Optional[int],
        lock_name: str,
        lock_token: str,
    ) -> None:
        if timeout_seconds is None:
            with tenant_scope(context.tenant_id):
                _invoke_handler(handler, payload, context)
            return

        process_context = multiprocessing.get_context("spawn")
        receiver, sender = process_context.Pipe(duplex=False)
        process = process_context.Process(
            target=_invoke_in_child,
            args=(handler, payload, context, context.tenant_id, sender),
            daemon=True,
        )
        try:
            process.start()
            sender.close()
        except Exception:
            receiver.close()
            sender.close()
            raise

        deadline = time.monotonic() + timeout_seconds
        renew_at = time.monotonic() + max(0.1, self._lock_ttl / 3.0)
        try:
            while process.is_alive():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    process.terminate()
                    process.join()
                    raise SchedulerTimeoutError(
                        f"task exceeded its {timeout_seconds}-second execution timeout"
                    )
                process.join(timeout=min(0.25, remaining))
                if time.monotonic() >= renew_at and process.is_alive():
                    if not self._lock.extend(
                        lock_name,
                        lock_token,
                        ttl_seconds=self._lock_ttl,
                    ):
                        process.terminate()
                        process.join()
                        raise SchedulerError(
                            "scheduler lock ownership was lost during task execution"
                        )
                    renew_at = time.monotonic() + max(0.1, self._lock_ttl / 3.0)

            if not receiver.poll():
                raise SchedulerError(
                    f"isolated task process exited with status {process.exitcode}"
                )
            succeeded, error_type = receiver.recv()
            if not succeeded:
                raise RemoteScheduledTaskError(error_type or "ScheduledTaskError")
            if process.exitcode != 0:
                raise SchedulerError(
                    f"isolated task process exited with status {process.exitcode}"
                )
        finally:
            receiver.close()
            if process.is_alive():
                process.terminate()
                process.join()
            process.close()

    def _run_backfill_one(
        self,
        job: ScheduledJob,
        backfill: ScheduledJobBackfill,
        *,
        worker: str,
        reference: datetime,
        lock_name: str,
        lock_token: str,
    ) -> Dict[str, Any]:
        if not self._dependencies_satisfied(
            job,
            as_utc(backfill.start_at),
            backfill=backfill,
        ):
            return {
                "job_key": job.job_key,
                "tenant_id": job.tenant_id,
                "backfill_id": backfill.id,
                "status": "waiting_dependency",
            }

        latest = self._session.execute(
            select(ScheduledJobRun)
            .where(
                ScheduledJobRun.backfill_id == backfill.id,
                ScheduledJobRun.tenant_id == job.tenant_id,
            )
            .order_by(ScheduledJobRun.attempt_number.desc())
            .limit(1)
        ).scalar_one_or_none()
        if latest is not None and latest.status in {"running", "interrupted"}:
            if latest.status == "running":
                latest.status = "interrupted"
                latest.finished_at = reference
                latest.error_type = "WorkerInterrupted"
                backfill.status = "interrupted"
                backfill.error_type = "WorkerInterrupted"
                backfill.updated_at = reference
                self._session.commit()
                details = {
                    "job_key": job.job_key,
                    "tenant_id": job.tenant_id,
                    "backfill_id": backfill.id,
                    "attempts": latest.attempt_number,
                    "status": "recovery_required",
                    "reason": "previous backfill attempt has no durable completion",
                }
                self._notify_failure(details)
                return details
            return {
                "job_key": job.job_key,
                "tenant_id": job.tenant_id,
                "backfill_id": backfill.id,
                "attempts": latest.attempt_number,
                "status": "recovery_required",
                "reason": "interrupted backfill awaits operator resolution",
            }

        attempt_number = backfill.attempt_count + 1
        if attempt_number > backfill.max_attempts:
            backfill.status = "failed"
            backfill.retry_at = None
            backfill.updated_at = reference
            self._session.commit()
            return {
                "job_key": job.job_key,
                "tenant_id": job.tenant_id,
                "backfill_id": backfill.id,
                "status": "backfill_failed",
                "error_type": "RetryLimitExceeded",
            }

        run = ScheduledJobRun(
            job_id=job.id,
            tenant_id=job.tenant_id,
            job_key=job.job_key,
            scheduled_for=as_utc(backfill.created_at),
            attempt_number=attempt_number,
            status="running",
            trigger_type="backfill",
            backfill_id=backfill.id,
            worker_id=worker,
            started_at=reference,
        )
        backfill.status = "running"
        backfill.attempt_count = attempt_number
        backfill.retry_at = None
        backfill.updated_at = reference
        self._session.add(run)
        self._session.commit()

        failure_error_type: Optional[str] = None
        try:
            handler = self._handlers.get(job.task_name)
            if handler is None:
                raise UnknownTaskError(
                    f"no handler registered for task {job.task_name!r}"
                )
            production = os.getenv(
                "PESAGUARD_ENVIRONMENT", "development"
            ).lower() in {"production", "prod"}
            if production and job.timeout_seconds is None:
                raise SchedulerError("production scheduled tasks require an execution timeout")
            if production and not _handler_accepts_context(handler):
                raise SchedulerError(
                    "production task handlers must accept ScheduledTaskContext "
                    "and honor its idempotency key"
                )
            context = ScheduledTaskContext(
                tenant_id=job.tenant_id,
                job_key=job.job_key,
                run_id=run.id,
                scheduled_for=as_utc(backfill.created_at),
                attempt_number=attempt_number,
                idempotency_key=(
                    f"scheduler-backfill:{job.tenant_id}:{job.job_key}:"
                    f"{backfill.idempotency_key}"
                ),
                trigger_type="backfill",
                window_start=as_utc(backfill.start_at),
                window_end=as_utc(backfill.end_at),
            )
            self._execute_handler(
                handler,
                dict(job.payload or {}),
                context,
                timeout_seconds=job.timeout_seconds,
                lock_name=lock_name,
                lock_token=lock_token,
            )
        except Exception as exc:  # noqa: BLE001 - persist each task failure before continuing
            failure_error_type = getattr(
                exc,
                "remote_error_type",
                type(exc).__name__,
            )
            run.status = "failed"
            run.finished_at = reference
            run.error_type = failure_error_type
            backfill.error_type = failure_error_type
            job.failure_count += 1
            job.last_error = failure_error_type
            if attempt_number < backfill.max_attempts:
                backoff = min(
                    job.retry_backoff_seconds * (2 ** (attempt_number - 1)),
                    MAX_RETRY_BACKOFF_SECONDS,
                )
                backfill.status = "retrying"
                backfill.retry_at = run.finished_at + timedelta(seconds=backoff)
                outcome_status = "backfill_retrying"
            else:
                backfill.status = "failed"
                backfill.retry_at = None
                outcome_status = "backfill_failed"
        else:
            run.status = "succeeded"
            run.finished_at = reference
            backfill.status = "succeeded"
            backfill.error_type = None
            backfill.retry_at = None
            job.run_count += 1
            job.last_error = None
            outcome_status = "backfill_succeeded"

        backfill.updated_at = reference
        job.updated_at = reference
        self._session.commit()
        if outcome_status == "backfill_failed":
            self._notify_failure(
                {
                    "job_key": job.job_key,
                    "tenant_id": job.tenant_id,
                    "backfill_id": backfill.id,
                    "attempts": attempt_number,
                    "status": outcome_status,
                    "error_type": failure_error_type,
                }
            )
        return {
            "job_key": job.job_key,
            "tenant_id": job.tenant_id,
            "backfill_id": backfill.id,
            "status": outcome_status,
            "attempts": attempt_number,
            "error_type": failure_error_type,
        }

    def _run_one(
        self,
        job: ScheduledJob,
        *,
        worker: str,
        now: Optional[datetime],
    ) -> Dict[str, Any]:
        lock_name = f"scheduler:{job.id}"
        token = self._lock.acquire(lock_name, ttl_seconds=self._lock_ttl)
        if token is None:
            logger.debug("scheduler.skip busy tenant=%s job=%s", job.tenant_id, job.job_key)
            return {
                "job_key": job.job_key,
                "tenant_id": job.tenant_id,
                "status": "skipped_locked",
            }

        try:
            reference = as_utc(now) if now else _now()
            backfill = self._session.execute(
                select(ScheduledJobBackfill)
                .where(
                    ScheduledJobBackfill.tenant_id == job.tenant_id,
                    ScheduledJobBackfill.job_id == job.id,
                    ScheduledJobBackfill.status.in_(
                        ("running", "queued", "retrying")
                    ),
                )
                .order_by(ScheduledJobBackfill.created_at.asc())
                .limit(1)
            ).scalar_one_or_none()
            if backfill is not None:
                if (
                    backfill.status == "retrying"
                    and backfill.retry_at is not None
                    and as_utc(backfill.retry_at) > reference
                ):
                    return {
                        "job_key": job.job_key,
                        "tenant_id": job.tenant_id,
                        "backfill_id": backfill.id,
                        "status": "waiting_backfill_retry",
                    }
                return self._run_backfill_one(
                    job,
                    backfill,
                    worker=worker,
                    reference=reference,
                    lock_name=lock_name,
                    lock_token=token,
                )

            due_at = job.retry_at or job.next_run_at
            if due_at is None or as_utc(due_at) > reference:
                return {
                    "job_key": job.job_key,
                    "tenant_id": job.tenant_id,
                    "status": "skipped_not_due",
                }

            job_id = job.id
            tenant_id = job.tenant_id
            job_key = job.job_key
            task_name = job.task_name
            scheduled_for = as_utc(job.next_run_at) if job.next_run_at else reference
            max_attempts = job.max_attempts
            retry_backoff_seconds = job.retry_backoff_seconds
            payload = dict(job.payload or {})
            if not self._dependencies_satisfied(job, scheduled_for):
                return {
                    "job_key": job_key,
                    "tenant_id": tenant_id,
                    "status": "waiting_dependency",
                }
            previous_run = self._session.execute(
                select(ScheduledJobRun)
                .where(
                    ScheduledJobRun.job_id == job_id,
                    ScheduledJobRun.tenant_id == tenant_id,
                    ScheduledJobRun.scheduled_for == scheduled_for,
                )
                .order_by(ScheduledJobRun.attempt_number.desc())
                .limit(1)
            ).scalar_one_or_none()
            if previous_run is not None and previous_run.status == "running":
                previous_run.status = "interrupted"
                previous_run.finished_at = reference
                previous_run.error_type = "WorkerInterrupted"
                self._session.commit()
                details = {
                    "job_key": job_key,
                    "tenant_id": tenant_id,
                    "attempts": previous_run.attempt_number,
                    "status": "recovery_required",
                    "reason": "previous attempt has no durable completion",
                }
                logger.error(
                    "scheduler.recovery_required tenant=%s job=%s attempt=%s",
                    tenant_id,
                    job_key,
                    previous_run.attempt_number,
                )
                self._notify_failure(details)
                return details
            if previous_run is not None and previous_run.status == "interrupted":
                return {
                    "job_key": job_key,
                    "tenant_id": tenant_id,
                    "attempts": previous_run.attempt_number,
                    "status": "recovery_required",
                    "reason": "interrupted attempt awaits operator resolution",
                }

            attempt_number = previous_run.attempt_number + 1 if previous_run else 1
            run = ScheduledJobRun(
                job_id=job_id,
                tenant_id=tenant_id,
                job_key=job_key,
                scheduled_for=scheduled_for,
                attempt_number=attempt_number,
                status="running",
                worker_id=worker,
                started_at=reference,
            )
            self._session.add(run)
            self._session.commit()

            handler = self._handlers.get(task_name)
            run_at = reference
            try:
                if handler is None:
                    raise UnknownTaskError(f"no handler registered for task {task_name!r}")
                production = os.getenv(
                    "PESAGUARD_ENVIRONMENT", "development"
                ).lower() in {"production", "prod"}
                if production and job.timeout_seconds is None:
                    raise SchedulerError(
                        "production scheduled tasks require an execution timeout"
                    )
                if production and not _handler_accepts_context(handler):
                    raise SchedulerError(
                        "production task handlers must accept ScheduledTaskContext "
                        "and honor its idempotency key"
                    )
                context = ScheduledTaskContext(
                    tenant_id=tenant_id,
                    job_key=job_key,
                    run_id=run.id,
                    scheduled_for=scheduled_for,
                    attempt_number=attempt_number,
                    idempotency_key=(
                        f"scheduler:{tenant_id}:{job_key}:{scheduled_for.isoformat()}"
                    ),
                )
                self._execute_handler(
                    handler,
                    payload,
                    context,
                    timeout_seconds=job.timeout_seconds,
                    lock_name=lock_name,
                    lock_token=token,
                )
            except Exception as exc:  # noqa: BLE001 - scheduler must not crash the tick
                run.status = "failed"
                run.finished_at = run_at
                failure_error_type = getattr(
                    exc,
                    "remote_error_type",
                    type(exc).__name__,
                )
                run.error_type = failure_error_type
                outcome_status = "retrying"
                failure_error = failure_error_type
            else:
                failure_error_type = None
                failure_error = None
                run.status = "succeeded"
                run.finished_at = run_at
                outcome_status = "ran"

            job = self._session.execute(
                select(ScheduledJob).where(
                    ScheduledJob.id == job_id,
                    ScheduledJob.tenant_id == tenant_id,
                )
            ).scalar_one()
            if outcome_status == "ran":
                job.run_count += 1
                job.last_error = None
                job.retry_at = None
                job.last_run_at = run_at
                job.next_run_at = run_at + timedelta(seconds=job.interval_seconds)
            else:
                job.failure_count += 1
                job.last_error = failure_error
                if attempt_number < max_attempts:
                    backoff = min(
                        retry_backoff_seconds * (2 ** (attempt_number - 1)),
                        MAX_RETRY_BACKOFF_SECONDS,
                    )
                    job.retry_at = run_at + timedelta(seconds=backoff)
                    logger.warning(
                        "scheduler.job_retry_scheduled tenant=%s job=%s attempt=%s retry_at=%s",
                        tenant_id,
                        job_key,
                        attempt_number,
                        job.retry_at.isoformat(),
                    )
                else:
                    job.retry_at = None
                    job.last_run_at = run_at
                    job.next_run_at = run_at + timedelta(seconds=job.interval_seconds)
                    outcome_status = "failed"
                    logger.error(
                        "scheduler.job_failed tenant=%s job=%s attempts=%s error_type=%s",
                        tenant_id,
                        job_key,
                        attempt_number,
                        failure_error_type,
                    )

            job.updated_at = _now()
            self._session.commit()
            if outcome_status == "failed":
                self._notify_failure(
                    {
                        "job_key": job_key,
                        "tenant_id": tenant_id,
                        "attempts": attempt_number,
                        "status": outcome_status,
                        "error_type": failure_error_type,
                    }
                )

            next_run_at = job.next_run_at
            logger.info(
                "scheduler.attempt_completed worker=%s tenant=%s job=%s status=%s next=%s",
                worker,
                tenant_id,
                job_key,
                outcome_status,
                next_run_at.isoformat(),
            )
            return {
                "job_key": job_key,
                "tenant_id": tenant_id,
                "status": outcome_status,
                "error_type": failure_error_type,
                "run_at": run_at.isoformat(),
                "next_run_at": next_run_at.isoformat(),
            }
        finally:
            self._lock.release(lock_name, token)

    def _notify_failure(self, details: Dict[str, Any]) -> None:
        if self._on_failure is None:
            return
        try:
            self._on_failure(details)
        except Exception:
            logger.exception(
                "scheduler.failure_notification_failed tenant=%s job=%s",
                details.get("tenant_id"),
                details.get("job_key"),
            )
