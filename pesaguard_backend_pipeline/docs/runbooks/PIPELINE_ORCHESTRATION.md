# Pipeline Orchestration

## When to use

Use this runbook when scheduled reconciliation is late or failing, a dependency
chain is blocked, a backfill is not progressing, a scheduler worker is
unhealthy, or an RQ dead-letter replay is stuck. Open an incident if
transaction outcomes may be incomplete or a tenant boundary may have failed.

## Triage

1. Record the UTC start time, tenant, job key or dead-letter ID, correlation
   IDs, and operator.
2. Check the dedicated worker and recent logs:

   ```sh
   docker compose ps scheduler_worker
   docker compose logs --since=30m scheduler_worker
   ```

3. If the worker is unhealthy, check the heartbeat timestamp, PostgreSQL
   availability, Redis health, and the tenant-registry credential before
   restarting it. `tenant_tick_failed` and `recovery_required` are incidents
   to investigate, not benign retries.
4. Read tenant-scoped job status and history with an authorized operations
   token:

   ```sh
   curl --fail-with-body \
     -H "Authorization: Bearer $OPERATIONS_TOKEN" \
     "$PESAGUARD_API_URL/api/v1/operations/scheduled-jobs"
   curl --fail-with-body \
     -H "Authorization: Bearer $OPERATIONS_TOKEN" \
     "$PESAGUARD_API_URL/api/v1/operations/scheduled-jobs/$JOB_KEY/history?limit=100"
   ```

5. Check `latest_run_status`, `failure_count`, `retry_at`, dependencies,
   backfill status counts, and attempt error types. Preserve audit, transaction,
   and provider evidence before taking recovery action.

## Scheduled job failures and dependency blocking

- A `retrying` job is governed by its stored retry time. Confirm the
  dependency failure is resolved before expecting downstream jobs to run.
- A `waiting_dependency` result is intentional when a prerequisite has not
  durably succeeded for the same scheduled occurrence. Do not remove the
  dependency edge to bypass a financial processing failure.
- A terminal `failed` outcome requires correction of the underlying issue
  before the next scheduled occurrence. The system records every attempt and
  emits critical logs; confirm that the configured Sentry alert path receives
  and routes the event.
- Do not re-register a job to reset its retry state. Registration is
  idempotent; use audited operator recovery only for interrupted attempts.

## Interrupted runs and safe resumption

An interrupted run may have committed effects before the worker crashed. Do
not assume it is safe to replay.

1. Inspect its history and reconcile the related transactions or external
   effects independently.
2. If the effect completed, resolve the run as `skip`. If and only if
   downstream idempotency has been verified, retry budget remains, and the
   handler's idempotency key is honored, an authorized operator may request a
   retry with explicit confirmation:

   ```sh
   curl --fail-with-body -X POST \
     -H "Authorization: Bearer $OPERATIONS_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"resolution":"retry","reason":"Verified no durable effect; downstream idempotency confirmed","confirm_idempotent_replay":true}' \
     "$PESAGUARD_API_URL/api/v1/operations/scheduled-runs/$RUN_ID/resolve"
   ```

3. Record the resolution and verify a new attempt appears in history. The
   authenticated API stores an audit event. Do not edit run or job rows
   directly.

## Historical reconciliation backfill

Only backfill already-ingested transaction records. The built-in handler
filters `ProcessedTransaction.received_at` over a UTC half-open interval and
does not query Safaricom for missing callbacks.

1. Confirm the outage window and exact tenant with the incident owner.
2. Use a stable, unique idempotency key and a reason tied to the incident.
   Windows are positive and capped at 366 days:

   ```sh
   curl --fail-with-body -X POST \
     -H "Authorization: Bearer $OPERATIONS_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"start_at":"2026-09-01T00:00:00Z","end_at":"2026-09-02T00:00:00Z","idempotency_key":"incident-123-sept-replay","reason":"Reconcile records received during the confirmed ingest outage"}' \
     "$PESAGUARD_API_URL/api/v1/operations/scheduled-jobs/$JOB_KEY/backfills"
   ```

3. A repeated request with the same key and identical details returns the
   durable existing request; changed details with a reused key are rejected.
4. Monitor job/backfill status and attempt history. A queued/running backfill
   or pending backfill retry takes priority over the recurring schedule.
5. Confirm reconciliation outcomes against the original provider evidence and
   verify repeated processing did not create duplicate financial effects.

## Dead-letter recovery

The RQ dead-letter queue is separate from scheduler run history.

1. Find the tenant-scoped record and inspect the original payload metadata,
   error, attempt count, and replay status:

   ```sh
   curl --fail-with-body \
     -H "Authorization: Bearer $OPERATIONS_TOKEN" \
     "$PESAGUARD_API_URL/api/v1/operations/dead-letters"
   ```

2. Correct the underlying cause and validate that replay is safe and
   idempotent. A replay requires the `resolve:discrepancies` permission and a
   non-empty reason:

   ```sh
   curl --fail-with-body -X POST \
     -H "Authorization: Bearer $OPERATIONS_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"reason":"Verified provider connectivity restored; replay is idempotent"}' \
     "$PESAGUARD_API_URL/api/v1/operations/dead-letters/$DEAD_LETTER_ID/replay"
   ```

3. Replays are bounded to three attempts. A replay already queued or in
   progress is not duplicated. A stale lease is marked failed and requires
   operator review; never clear the lease or reset its attempt counter by
   directly updating the database.
4. Verify the durable replay result, resulting transaction disposition, and
   tenant-specific audit event. A queue submission is not proof that the
   financial processing outcome completed.

## Recovery and closure

- Restart only after the root cause is understood and dependencies are
  healthy. The dedicated Compose worker can be restarted without deleting
  durable jobs or attempts:

  ```sh
  docker compose restart scheduler_worker
  ```

- Verify worker health, heartbeat progress, tenant-scoped job state, run
  history, audit evidence, and queue/dead-letter counts.
- Confirm downstream reconciliation and idempotency before declaring
  transaction processing recovered.
- Document impact, affected tenants, all operator actions, evidence, root
  cause, and follow-up owners. Escalate data-integrity uncertainty or possible
  cross-tenant access to the service owner and security response process.

See [Scheduler Operations](../operations/SCHEDULER.md) for component,
deployment, authorization, timeout, retry, and RLS details.
