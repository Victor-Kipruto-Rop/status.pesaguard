# Alerts

> Design and operating reference for **Observability / Alerts**.

## Purpose

This document defines the role of **Alerts** within PesaGuard. It provides a durable reference for signals, telemetry, health, service-level objectives, and actionable alerting, with enough detail for implementation, review, operations, and future change.

## Current position

PesaGuard currently runs a Flask and SQLAlchemy backend with PostgreSQL as the production store, Redis and RQ for asynchronous work, Kafka for event transport, and Alembic for schema migration. M-Pesa/Daraja is the active payment scope. Capabilities described here must be marked as implemented, in progress, or planned rather than implied.

## Alert center implementation

The alert center is implemented in the backend and tenant dashboard:

- Typed transaction, reconciliation, fraud, security, and provider events can enqueue notification jobs; the worker normalizes each to a category, source, and severity. Reconciliation outbox events carry a stable source event ID. The worker stores the alert and its initial in-app delivery before attempting external channels.
- `GET /api/v1/alerts` provides tenant-scoped, paginated filtering by status, severity, category, assignee, and time. The dashboard polls for updates and supports paging, acknowledgement, assignment, investigation notes, resolution, and escalation.
- `GET /api/v1/alerts/{id}/events` returns paginated append-only history. Repeated source events increment the duplicate count and append `alert.duplicate_suppressed`; replaying the same event ID does not create another history entry or send another notification.
- Configured alert email is enqueued transactionally in the communications outbox. Delivery attempts and provider status are shown through the linked communications notification. Communications retry and dead-letter operations remain available under `/api/v1/communications`.
- Slack and SMS attempts are persisted before network calls. Failed attempts are retried by RQ using the same event ID; `PESAGUARD_ALERT_MAX_ATTEMPTS` bounds attempts per retry generation. Exhausted channels become `dead_letter`, and authorized operators can start another generation from the notification center without resetting lifetime attempt history. Channels without required credentials become `not_configured` and are not automatically retried.
- Escalation can use active severity rules and an on-call shift from the manual escalation action. Webhook escalation requires its configured escalation worker; the manual API does not imply automatic time-based escalation.

Apply Alembic revision `20261018_alert_center_workflow` before enabling these routes. Set `ALERT_EMAIL_RECIPIENTS` or tenant-specific recipient settings to enable operational email, and provide selected provider credentials through the deployment secret store. Run and monitor the notification RQ worker and communications outbox worker. Do not place credentials in committed environment examples.

### Operator checks

1. Confirm the alert migration is at the deployed database head.
2. Confirm notification and communications workers are consuming their queues and that no alert deliveries remain in `processing` beyond the worker timeout.
3. Review `GET /api/v1/alerts?status=open` and `GET /api/v1/alerts/{id}` for delivery status, attempt counts, and error codes; inspect paginated event history for causal events and operator actions.
4. Retry direct Slack/SMS dead letters from the alert notification center after checking provider configuration. Replay communication email dead letters through `/api/v1/communications/dead-letters/{entry_id}/replay`.
5. Verify active escalation rules and on-call shifts before relying on escalation actions.

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

## Validation

At minimum, validate syntax and imports, focused unit behavior, cross-tenant isolation, migration compatibility, API contract behavior, and failure/retry paths. For integration changes, run the PostgreSQL, Redis, and Kafka checks when those services are available and record unavailable dependencies explicitly.

## Open work

- Replace this design baseline with implementation-specific diagrams, schemas, examples, and measured SLOs as the capability matures.
- Link the final implementation, migration revision, tests, dashboards, and runbook from this document.
- Review this document whenever the public contract, ownership boundary, or recovery behavior changes.

## Alert rule reference

The following alert rules are defined in `monitoring/alerts.yml` and evaluated by Prometheus:

### Data pipeline alerts
| Alert | Expression | For | Severity | Service |
|-------|------------|-----|----------|---------|
| `PesaGuardTransactionOutboxBacklog` | `pesaguard_transaction_outbox_pending > 1000` | 10m | critical | ingestion |
| `PesaGuardReconciliationOutboxBacklog` | `pesaguard_reconciliation_outbox_pending > 1000` | 10m | critical | reconciliation |
| `PesaGuardReconciliationProcessingStuck` | `pesaguard_reconciliation_processing > 0` | 30m | warning | reconciliation |
| `PesaGuardDeadLetterGrowth` | `pesaguard_dead_letters_total > 100` | 5m | warning | ingestion |
| `PesaGuardEventRetriesGrowing` | `increase(pesaguard_event_retries_total[10m]) > 100` | 10m | warning | redpanda |

### Notification delivery alerts
| Alert | Expression | For | Severity | Service |
|-------|------------|-----|----------|---------|
| `PesaGuardNotificationQueueBacklog` | `pesaguard_notification_queue_depth > 500` | 10m | critical | communications |
| `PesaGuardNotificationDeliveryFailureRate` | `(pesaguard_notification_deliveries_failed / max(pesaguard_notification_deliveries_total + pesaguard_notification_deliveries_failed, 1)) > 0.05` | 10m | critical | communications |
| `PesaGuardNotificationDeliveryPendingHigh` | `pesaguard_notification_deliveries_pending > 1000` | 15m | warning | communications |

### M-Pesa ingestion alerts
| Alert | Expression | For | Severity | Service |
|-------|------------|-----|----------|---------|
| `PesaGuardMpesaIngestionLatencyHigh` | `pesaguard_mpesa_ingestion_latency_p95_seconds > 300` | 10m | warning | ingestion |

### Backup alerts
| Alert | Expression | For | Severity | Service |
|-------|------------|-----|----------|---------|
| `PesaGuardBackupStale` | `pesaguard_backup_age_seconds > 93600` | 30m | critical | backup |
| `PesaGuardBackupFailed` | `pesaguard_backup_last_success == 0` | 15m | critical | backup |

### API / infrastructure alerts
| Alert | Expression | For | Severity | Service |
|-------|------------|-----|----------|---------|
| `PesaGuardErrorRateHigh` | `pesaguard_error_rate > 0.05` | 10m | warning | api |
| `PesaGuardRequestLatencyHigh` | `pesaguard_request_p99_ms > 2000` | 10m | warning | api |
| `PesaGuardDatabasePoolSaturation` | `pesaguard_db_pool_usage > 0.8` | 10m | warning | database |
| `PesaGuardKafkaConsumerLagHigh` | `pesaguard_kafka_consumer_lag_max > 10000` | 10m | critical | redpanda |

### Fraud / security alerts
| Alert | Expression | For | Severity | Service |
|-------|------------|-----|----------|---------|
| `PesaGuardFraudWorkerFailures` | `increase(pesaguard_business_fraud_engine_failures_total[10m]) > 0` | 5m | critical | fraud |
| `PesaGuardReconciliationWorkerFailures` | `increase(pesaguard_business_reconciliation_failures_total[10m]) > 0` | 5m | critical | reconciliation |
| `PesaGuardSecurityEventsHigh` | `increase(pesaguard_business_security_events_total[10m]) > 25` | 10m | warning | security |
| `PesaGuardAuthenticationRiskBlocksHigh` | `sum(increase(pesaguard_auth_risk_decisions_total{action=~"block\|review"}[10m])) > 5` | 5m | critical | security |
| `PesaGuardAuthenticationStepUpHigh` | `sum(increase(pesaguard_auth_risk_decisions_total{action="require_mfa"}[10m])) > 50` | 10m | warning | security |
| `PesaGuardAuthenticationRateLimiterUnavailable` | `increase(pesaguard_business_auth_rate_limiter_unavailable_total[5m]) > 0` | 1m | critical | security |
