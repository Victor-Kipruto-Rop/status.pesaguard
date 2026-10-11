# PostgreSQL and Data Integrity

## Evidence observed

- The SQLAlchemy transaction model includes tenant/provider-scoped uniqueness,
  tenant nonempty constraints, positive amount checks, and `Numeric(18,2)` amounts.
- The Alembic directory contains 56 revision files; read-only `alembic heads`
  reported one head: `20261009_settlement_scheduler`.
- `database_service.py` enables pool pre-ping and uses PostgreSQL defaults of
  pool size 10, overflow 20, timeout 30 seconds, recycle 1800 seconds, and a
  configurable five-second connection timeout.
- Reconciliation code persists processing state and commits Kafka offsets only
  after processing returns success; source behavior was not integration-tested.
- Tests exist for API idempotency, tenant isolation, export/report scoping,
  PostgreSQL concurrency, and integrity constraints. Their presence is not test
  evidence because the suite did not collect.

## Gaps

- Effective connection demand is unknown. Pool limits are per engine/process, and
  aggregate connections across APIs, workers, jobs, and replicas were not bounded
  against the target Postgres `max_connections`.
- No PostgreSQL service was started and no migrations were applied. Migration
  ordering was checked only for a single graph head, not for an upgrade, rollback,
  data migration duration, or old/new application compatibility.
- No target workload benchmark or query-plan/index analysis was run.
- PostgreSQL row-level security directives/policies were not found in the inspected
  Python/SQL source. Tenant safety must be verified across ORM, raw SQL, async,
  cached, administrative, and export paths.
- The Compose data volume and WAL archive are both local named volumes on the same
  host. Neither constitutes off-site backup.
- No restore, point-in-time recovery, failure injection, or financial reconciliation
  integrity drill was executed.

## Required database acceptance tests

Use isolated PostgreSQL with generated test tenants and data. Verify duplicate
ingestion under concurrent requests, rollback after mid-transaction failure,
idempotent replays, atomic state transitions, tenant-scoped foreign keys/uniqueness,
late and reversed transactions, migration from the deployed base version, and
restore plus reconciliation totals. Capture lock waits, pool saturation, and
database size for a documented synthetic workload. Do not use live payment data.

