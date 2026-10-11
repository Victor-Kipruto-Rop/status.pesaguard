# Monitoring, Operations, Backup, and Recovery

## Monitoring evidence

- FastAPI provides separate `/live`, `/ready`, `/health`, and `/version` routes.
  `/live` is process liveness; `/health` and `/ready` build dependency status.
- Health code checks PostgreSQL, Kafka, Redis, and Daraja with bounded connection
  timeouts. Kafka and Redis affect overall healthy status only when their
  `PESAGUARD_HEALTH_REQUIRE_*` flags are enabled; current production values are
  unknown.
- Compose defines Prometheus, Alertmanager, Grafana, and an OTEL collector with
  configuration mounts. The AWS merged config publishes all monitoring/telemetry
  ports on all interfaces.
- CI defines container scanning and SBOM generation; an executed result was not
  available.

No running alert route, paging test, persistent metrics history, dashboard review,
TLS expiry monitoring, disk/backup alert, or privacy review of live telemetry was
verified. Prometheus and Grafana have no explicit persistent data volumes in the
base Compose definition.

## Backup and recovery evidence

- `backup_postgres.py` supports retention (default 30 days), backup integrity
  handling, encryption commands, off-site upload commands, and refuses production
  backup execution when encryption/upload commands are missing.
- A systemd backup service/timer and restore-drill utility exist in source.
- The Compose WAL archive is a named volume on the same VPS as the database.
- `DATA_RESIDENCY.md` requires backup-region confirmation; provider/region remain
  pending.

No backup was run, no off-site destination/key was configured or inspected, and no
restore drill was executed. Therefore RPO/RTO are **UNVERIFIED**, not met. The
runbook's targets (near-zero RPO and <15-minute RTO) are aspirations until measured.

## Recovery gaps and minimum plan

1. **VPS failure / provider outage:** restore encrypted off-site Postgres backup and
   replay WAL in a clean target; provision app/broker/cache, restore secrets, then
   validate before DNS cutover. Current one-host design has no automatic failover.
2. **Database corruption / accidental deletion:** preserve forensic copy, restore
   to isolated target, verify tenant counts, constraints, balances/reconciliation
   totals, then authorize cutover.
3. **Disk exhaustion:** alert on free bytes/inodes, database/WAL/broker growth and
   backup staging space; pause non-critical ingestion only under an approved
   operational procedure.
4. **Failed deployment/migration:** pin previous image digest, stop promotion,
   preserve database state, use documented forward fix or tested restore; do not
   assume schema rollback is safe.
5. **Compromised credentials:** revoke keys/sessions, rotate secrets through a
   tested change procedure, review access/audit logs, and verify tenants/providers.
6. **Redis or Redpanda outage:** verify outbox persistence and redelivery; alert on
   queue/consumer lag; do not claim every queued notification is durable.
7. **Incomplete reconciliation:** identify uncommitted offsets and leased/outbox
   work; replay idempotently and reconcile persisted outcomes before acknowledging.

Proposed RPO/RTO must be approved by the business and proven in timed drills; this
report does not certify either.

