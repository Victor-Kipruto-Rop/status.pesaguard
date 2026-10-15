# Workflow Orchestration and Scheduler Operations

The scheduler is the durable orchestration boundary for tenant-scoped,
allow-listed background work. It supports recurring jobs, prerequisite
dependencies, bounded retries, isolated timeouts, operator-approved recovery,
and auditable historical reconciliation backfills. It does not accept
arbitrary task import paths or execute payout, refund, settlement-transfer, or
other money-movement operations.

## Components and deployment

- `scheduler.py` owns job definitions, dependency edges, execution state,
  per-attempt history, retries, timeout handling, backfills, and recovery.
- `scheduled_tasks.py` exposes the allow-listed
  `reconciliation.pending_transactions` handler. Its normal pass is capped at
  500 transactions; historical windows filter by transaction receipt time and
  do not fetch missing provider records.
- `scheduler_worker.py` is a dedicated worker process. It enumerates active
  tenants through the least-privilege registry credential and executes each
  tenant under a transaction-local tenant context.
- Docker Compose starts the worker as `scheduler_worker`; its heartbeat file
  is used as a process-liveness check and continues during long bounded task
  execution. It does not prove that a database operation or scheduled job
  succeeded. Keep
  `PESAGUARD_SCHEDULER_HEARTBEAT_STALE_SECONDS` greater than the normal
  `SCHEDULER_POLL_SECONDS` plus expected maximum tick duration.
- Redis provides the distributed per-job lock. Production fails closed if
  Redis is unavailable; the in-process lock is for single-instance
  development only.
- `SENTRY_DSN` routes terminal job failures and per-tenant tick exceptions to
  Sentry. Without a configured receiver, structured critical/error logs and
  container health remain the available operational signals.

The worker requires `DATABASE_URL`, `REDIS_URL`, and
`PESAGUARD_TENANT_REGISTRY_DATABASE_URL`. In PostgreSQL, the registry URL must
use the isolated read-only tenant-registry role described in
[`TENANT_ISOLATION.md`](./TENANT_ISOLATION.md); it must not be the application,
migration, superuser, or `BYPASSRLS` credential. Compose maps
`PESAGUARD_TENANT_REGISTRY_DATABASE_URL_DOCKER` to the worker's registry URL.
Do not put production credentials in the checked-in environment example.

Apply migrations through `20261015_workflow_orchestration` before enabling the
worker. The migration adds `timeout_seconds`, tenant-local dependency and
backfill tables, and backfill references on run history. PostgreSQL migrations
require `PESAGUARD_APPLICATION_DB_ROLE`. The new tenant-owned tables use
enabled and forced RLS with tenant policies and grants for that runtime role.
Verify effective privileges, role membership, policies, and tenant-scoped
behavior under the actual runtime credentials after deployment; migration
success alone is not deployment evidence.

## Execution and durability model

- One tick considers at most 50 due jobs per tenant. The worker rotates the
  first tenant served between ticks. Due work remains durable in PostgreSQL
  across restarts.
- Each job attempt is inserted as `running` before invoking its handler. The
  final attempt status and schedule/backfill state are committed after the
  handler returns. A per-job distributed lock excludes competing workers.
- Retries use the configured `max_attempts` (1–5) and exponential backoff,
  capped at five minutes. A terminal failure is recorded and sent to the
  configured failure notification hook. Attempt history is retained in
  `scheduled_job_runs`.
- Production execution requires a configured timeout (1–3600 seconds) and a
  handler that accepts `ScheduledTaskContext`. Timed handlers run in an
  isolated spawned process; the parent renews the distributed lock while
  waiting and terminates the child at timeout or lock loss.
- A timeout cannot undo side effects already committed by the child. Every
  handler must make writes and external effects idempotent using the stable
  `ScheduledTaskContext.idempotency_key`. The key stays stable across retries
  for the same scheduled occurrence or backfill; `run_id` and
  `attempt_number` change.
- PostgreSQL work must establish trusted tenant context before business
  queries. The worker obtains tenant IDs from the registry role, not from job
  payloads. `tenant_id` is never accepted from an operator-supplied task
  payload.

## Dependency-aware execution

Register a dependency by adding a tenant-local edge from a job to its
prerequisite. Cycles and cross-tenant edges are rejected. The prerequisite
and child must have the same interval and next scheduled time so runs can be
paired deterministically. The child remains `waiting_dependency` until the
prerequisite has a durable successful outcome for that same scheduled
occurrence. A failed or retrying parent therefore blocks its dependent; it
does not silently run stale work.

Backfill dependencies require a successful parent backfill with the same
tenant, idempotency key, and exact time window.

## Historical backfills

Backfill requests are durable, tenant-scoped, idempotent, and require an
operator identity and reason. The interval must be positive and no longer
than 366 days; the idempotency key is unique per job and tenant. A repeated
request with the same key and identical request details returns the existing
record; reusing the key for different details is rejected.

Backfills run through the normal scheduler worker, use the registered task's
timeout and retry policy, and create one durable run-history row per attempt.
While a backfill is queued, running, or waiting for retry, it takes priority
over the recurring execution for that job. This prevents the regular schedule
from bypassing a delayed backfill retry. The built-in reconciliation handler
uses `[start_at, end_at)` against `ProcessedTransaction.received_at`; it
reconciles already-ingested rows and does not fetch provider history or restore
missing callbacks.

## Authenticated operator API

These HTTP endpoints derive the tenant from the authenticated user. Clients
cannot choose a different tenant by sending a `tenant_id` request field.

| Method and path | Permission | Purpose |
|---|---|---|
| `GET /api/v1/operations/scheduled-jobs` | `read:analytics` | List tenant job status, dependencies, and backfill counts |
| `GET /api/v1/operations/scheduled-jobs/{job_key}/history` | `read:analytics` | Read bounded attempt/recovery history |
| `POST /api/v1/operations/scheduled-jobs` | `manage:settings` | Register the allow-listed reconciliation task |
| `POST /api/v1/operations/scheduled-jobs/{job_key}/backfills` | `manage:settings` | Enqueue an audited historical window |
| `POST /api/v1/operations/scheduled-runs/{run_id}/resolve` | `manage:settings` | Resolve an interrupted scheduled or backfill attempt |
| `GET /api/v1/operations/dead-letters` | `read:analytics` | Inspect tenant-scoped dead-letter metadata |
| `POST /api/v1/operations/dead-letters/{id}/replay` | `resolve:discrepancies` | Request a reasoned, bounded dead-letter replay |

Job registration requires `job_key`, `interval_seconds`, and
`timeout_seconds`; optional fields are `first_run_at`, `max_attempts`,
`retry_backoff_seconds`, `batch_size`, and `depends_on` (job keys). The task
name is fixed to `reconciliation.pending_transactions`.

Backfills require UTC `start_at`, `end_at`, an `idempotency_key` of at least
eight characters, and a non-empty `reason`. Interrupted-run resolution accepts
`resolution` (`retry` or `skip`) and `reason`. A retry additionally requires
`confirm_idempotent_replay: true`; the operator must first check for completed
side effects and remaining retry budget. All these operator changes write
tenant-scoped audit records.

## Interrupted-run and dead-letter recovery

If a worker crashes after a run is recorded as `running`, the next lock owner
marks it `interrupted` and reports `recovery_required`; automatic replay is
blocked because the handler may have committed partial or complete external
effects. Inspect the run history and reconcile effects before an authorized
operator uses the resolution API. `skip` records the decision and advances
the schedule. `retry` is explicit, requires idempotency confirmation, and is
limited by the original retry policy.

RQ dead-letter replay is a separate recovery path. A dead-letter replay is
tenant-scoped, claimed under a database row lock, durably recorded before
queueing, requires an operator reason, and is limited to three attempts. A
stale replay lease is marked failed and requires operator inspection; do not
blindly reset or delete it. Follow the
[`pipeline orchestration runbook`](../runbooks/PIPELINE_ORCHESTRATION.md) and
the general [reconciliation failure runbook](../runbooks/RECONCILIATION_FAILURE.md).

## Monitoring and deployment checks

1. Confirm `scheduler_worker` is alive and its heartbeat is advancing. Treat
   this as process liveness only, not proof that jobs are completing.
2. Check structured `pesaguard.scheduler` and `pesaguard.scheduler_worker`
   logs for `job_failed`, `recovery_required`, `tenant_tick_failed`, and
   `operator_alert` events.
3. Confirm Sentry receives terminal scheduler failures if Sentry is the
   configured incident channel; verify notification routing independently.
4. Inspect job `failure_count`, `retry_at`, latest status, run history, and
   backfill counts through the authenticated operations endpoints.
5. Inspect Redis availability and PostgreSQL connectivity/RLS when a tick is
   not progressing. A missing heartbeat can mean the registry lookup or a
   tick is blocked; it is not a successful no-op.
6. After deployment, verify table RLS/grants and test cross-tenant reads and
   writes using the real application role and tenant-registry role.

The scheduler orchestration tests include a disposable PostgreSQL 16 run of
the full Alembic chain, the workflow migration downgrade/upgrade, runtime-role
grants, forced RLS, pooled-connection tenant-context isolation, and tenant
registry-role boundaries. This validates the migration and tested policies
against PostgreSQL; it does not verify the deployed database's credentials,
role membership, grants, policies, or data. Repeat the checks using target
deployment credentials after rollout. Performance capacity and delivery to an
external alert receiver must also be measured and verified in the target
deployment; they are not certified by local tests.
