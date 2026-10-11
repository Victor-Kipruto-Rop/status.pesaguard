# Scheduler Operations

> Operating reference for the durable job scheduler and the distributed lock it
> relies on. Implementation: `pesaguard_backend_pipeline/scheduler.py` and
> `pesaguard_backend_pipeline/distributed_lock.py`.

## Components

- **Scheduler** (`scheduler.py`): durable, tenant-scoped job scheduling backed
  by the `scheduled_jobs` table (Alembic revision
  `20261009_settlement_scheduler`). Job definitions survive restarts; due-run
  decisions are computed from an injected clock for deterministic testing.
- **DistributedLockService** (`distributed_lock.py`): Redis `SET NX PX`
  acquire with ownership tokens and Lua safe-release/extend. Falls back to a
  thread-safe in-process lock for single-instance development; fails closed in
  production when Redis is not configured.

## Execution model

- Each scheduler tick selects enabled jobs whose `next_run_at <= now`
  (bounded batch, default 50 per tick).
- Before executing a job, the worker acquires a per-job distributed lock
  (`scheduler:<job_id>`); a busy lock is a benign skip (`skipped_locked`), so a
  job runs at-most-once across all scheduler replicas.
- Successful and failed runs both re-arm the schedule deterministically
  (`next_run_at = run_at + interval_seconds`) and increment `run_count` /
  `failure_count` with the last error text persisted for operators.

## Safety invariants

- Every query is tenant-scoped; workers never mutate another tenant's job.
- Handlers must be idempotent: a crash between execution and the persisted
  re-arm may replay a run after the lock TTL expires.
- Schedule state is never advanced by a worker that failed to acquire the lock.

## Tests

`tests/test_scheduler.py` (deterministic clock, at-most-once, busy-lock skip,
failure accounting, tenant isolation) and
`tests/test_infra_locking_scheduling.py` (integration across lock, scheduler,
and discovery).
