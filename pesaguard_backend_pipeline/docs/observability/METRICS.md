# Metrics

> Design and operating reference for **Observability / Metrics**.

## Purpose

This document defines the role of **Metrics** within PesaGuard. It provides a durable reference for signals, telemetry, health, service-level objectives, and actionable alerting, with enough detail for implementation, review, operations, and future change.

## Current position

PesaGuard currently runs a Flask and SQLAlchemy backend with PostgreSQL as the production store, Redis and RQ for asynchronous work, Kafka for event transport, and Alembic for schema migration. M-Pesa/Daraja is the active payment scope. Capabilities described here must be marked as implemented, in progress, or planned rather than implied.

## Design principles

- **Tenant isolation:** every query, mutation, export, cache key, event, audit record, and replay operation carries an authorized tenant and provider-account scope.
- **Durability first:** acknowledge work only after the required durable state or idempotency record is written.
- **Explicit contracts:** validate input, normalize provider data, return stable error shapes, and version public interfaces.
- **Safe failure:** retry transient failures with bounded backoff; dead-letter malformed or exhausted work; never silently discard financial events.
- **Evidence by default:** correlation IDs, structured logs, metrics, append-only audit events, and operator actions must explain what happened.
- **Migration discipline:** use Alembic revisions, backfill safely, and keep compatibility paths deliberate.

## Scope

### Included

- The responsibilities and interfaces implied by this document.
- PostgreSQL persistence and tenant-aware data access.
- Authentication, authorization, validation, observability, and recovery controls.
- Focused tests for normal paths, boundary conditions, isolation, retries, and failure recovery.

### Excluded

- Unscoped cross-tenant access or undocumented administrative bypasses.
- Production schema creation through `Base.metadata.create_all()`.
- Storing plaintext credentials, API keys, tokens, or provider secrets.
- Expanding payment rails beyond the approved product scope without a separate decision.

## Reference workflow

1. Authenticate the caller or verify the provider signature.
2. Resolve tenant and provider-account identity from trusted context.
3. Validate and normalize the request or event.
4. Enforce idempotency before performing a side effect.
5. Persist the durable record and audit event in a transaction.
6. Publish or enqueue work through the outbox or worker boundary.
7. Reconcile, notify, measure, and expose a traceable result.

## Interfaces and invariants

- Public API routes belong under `/api/v1` unless a documented compatibility alias exists.
- IDs are not globally trusted: detail lookups must include tenant and account predicates.
- A retry must be safe to run more than once.
- A successful response must not claim durable processing when the required persistence step failed.
- Audit entries are append-only and must not contain secret material.

## Security and privacy

Use least privilege, short-lived credentials, hashed API keys, explicit scopes, expiry, revocation, rotation, and last-used metadata. Redact sensitive fields at logging boundaries. Apply retention and archival policies by tenant, legal hold, and residency requirements.

## Operations

Monitor request success and latency, reconciliation health, database pool health, Kafka and outbox lag, retry and dead-letter rates, provider errors, audit delivery, and backup freshness. Every alert should identify an owner, a runbook, a severity, and a recovery target.

Authentication risk telemetry is exported as `pesaguard_auth_risk_decisions_total{risk_level,action}` and `pesaguard_auth_risk_signals_total{signal}`. Labels are fixed vocabularies; do not add tenant IDs, account IDs, IPs, device IDs, or user-agent strings. Shared decision totals are also available through the bounded `pesaguard_business_auth_risk_*` counters. Redis fail-closed auth denials increment `pesaguard_business_auth_rate_limiter_unavailable_total` and `pesaguard_business_security_events_total`.

Alert rules detect sustained risk blocks/reviews, elevated risk-based MFA volume, and any auth limiter outage. A metric alert is a triage signal, not evidence of compromise; correlate it with authentication audit records and the approved risk policy. Follow `docs/runbooks/AUTHENTICATION_RISK.md` for review and recovery.

## Validation

At minimum, validate syntax and imports, focused unit behavior, cross-tenant isolation, migration compatibility, API contract behavior, and failure/retry paths. For integration changes, run the PostgreSQL, Redis, and Kafka checks when those services are available and record unavailable dependencies explicitly.

## Open work

- Replace this design baseline with implementation-specific diagrams, schemas, examples, and measured SLOs as the capability matures.
- Link the final implementation, migration revision, tests, dashboards, and runbook from this document.
- Review this document whenever the public contract, ownership boundary, or recovery behavior changes.

## Metric reference

The following Prometheus metrics are exported by `metrics.py` and scrapable at `/metrics`:

### Request/latency metrics
| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `pesaguard_requests_per_second` | Gauge | — | Application requests observed per second (scrape window) |
| `pesaguard_error_rate` | Gauge | — | Application error rate (failed / total requests) |
| `pesaguard_request_p50_ms` | Gauge | — | Application request p50 latency |
| `pesaguard_request_p95_ms` | Gauge | — | Application request p95 latency |
| `pesaguard_request_p99_ms` | Gauge | — | Application request p99 latency |
| `pesaguard_request_timeouts_total` | Gauge | — | Application request timeouts |

### Database metrics
| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `pesaguard_db_connections` | Gauge | — | Active database connections |
| `pesaguard_db_pool_usage` | Gauge | — | Database pool usage ratio |
| `pesaguard_db_query_p95_ms` | Gauge | — | Database query p95 latency |
| `pesaguard_db_locks` | Gauge | — | Unresolved database locks |
| `pesaguard_db_deadlocks_total` | Gauge | — | Database deadlocks |

### Kafka / event bus metrics
| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `pesaguard_kafka_consumer_lag` | Gauge | — | Kafka consumer lag (sum across partitions) |
| `pesaguard_kafka_consumer_lag_max` | Gauge | — | Maximum Kafka consumer partition lag |
| `pesaguard_event_retries_total` | Counter | — | Event retry count |

### Reconciliation metrics
| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `pesaguard_reconciliation_latency_p95_seconds` | Gauge | — | 95th percentile reconciliation processing time |
| `pesaguard_reconciliation_processing` | Gauge | — | Reconciliation records currently processing |
| `pesaguard_reconciliation_outbox_pending` | Gauge | — | Reconciliation outbox rows awaiting publication |
| `pesaguard_discrepancies_open` | Gauge | tenant_id | Current unresolved discrepancies |
| `pesaguard_reconciliation_matches_total` | Gauge | status | Reconciliation decisions by canonical status |

### M-Pesa ingestion metrics
| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `pesaguard_mpesa_ingestion_latency_p95_seconds` | Gauge | — | 95th percentile M-Pesa callback ingestion latency |

### Notification delivery metrics
| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `pesaguard_notification_queue_depth` | Gauge | — | Notification outbox entries pending/retrying/leased |
| `pesaguard_notification_deliveries_total` | Gauge | — | Total notification deliveries that reached delivered state |
| `pesaguard_notification_deliveries_failed` | Gauge | — | Total notification deliveries that failed/dead-lettered/bounced/complained |
| `pesaguard_notification_deliveries_pending` | Gauge | — | Total notification deliveries pending acceptance/submission |

### Business metrics
| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `pesaguard_business_transactions_received` | Counter | — | Transactions received |
| `pesaguard_business_transactions_reconciled` | Counter | — | Transactions reconciled |
| `pesaguard_business_fraud_alerts` | Counter | — | Fraud alerts |
| `pesaguard_business_fraud_engine_failures` | Counter | — | Fraud engine failures |
| `pesaguard_business_reconciliation_failures` | Counter | — | Reconciliation failures |
| `pesaguard_business_exceptions` | Counter | — | Exceptions |
| `pesaguard_business_duplicates` | Counter | — | Duplicates |
| `pesaguard_business_security_events` | Counter | — | Security events |

### Backup metrics
| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `pesaguard_backup_age_seconds` | Gauge | — | Age of newest local database backup |
| `pesaguard_backup_last_status` | Gauge | — | Last backup status |
