# Operations

## Health

- `GET /health/live` checks process liveness.
- `GET /health/ready` checks application readiness.
- In Kubernetes production, Actuator liveness/readiness and Prometheus metrics
  listen on the private management port `9090`; Kubernetes probes and the
  monitoring namespace are the only permitted network peers. Readiness includes
  PostgreSQL and Redis.
- Health responses must not disclose dependency credentials or internal connection details.

See the [operations runbook](./operations-runbook.md) for the protected CI
environment configuration, immutable image promotion, deployment checks, and
the operational verifications that still require target-environment access.

## Database

Flyway runs at startup. The application uses Hibernate schema validation, not automatic schema mutation. Before applying migrations to a shared environment:

1. review the migration and data impact;
2. take and verify a backup/restore point;
3. run the migration in staging with representative volume;
4. verify constraints, indexes, and application startup;
5. monitor errors, latency, and audit verification.

The current migration creates the developer-platform foundation only. It does not create payment, reconciliation, or production-environment tables.

## Deployment

The included `docker-compose.yml` is a local/staging convenience, not a production orchestrator. Supply secrets through the environment; do not bake them into images. The container runs as an unprivileged UID. Terminate TLS and enforce request size/timeout/rate limits at the ingress or gateway.

Set registration to `false` unless public self-registration is explicitly approved. Restrict CORS to the deployed portal origin. Do not expose database ports publicly.

## Backup and recovery

PostgreSQL backups are a separate operational responsibility. Validate restore procedures and audit retention before relying on them. Never delete financial/audit data to recover space; use approved archival and retention policies.

## Alerts to add with deployment configuration

- readiness failures and database connection exhaustion;
- elevated 5xx, 401, 403, and 429 rates;
- registration/login anomaly signals;
- failed Flyway/startup checks;
- audit verification failures;
- container restarts and resource saturation.

Thresholds must be set from measured production baselines; no performance or reliability number is claimed by this repository.
