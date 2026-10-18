# Kafka

> Design and operating reference for **Infrastructure / Kafka**.

## Purpose

This document defines the role of **Kafka** within PesaGuard. It provides a durable reference for runtime environments, platform dependencies, networking, deployment, and capacity, with enough detail for implementation, review, operations, and future change.

## Current position

PesaGuard currently runs a Flask and SQLAlchemy backend with PostgreSQL as the production store, Redis and RQ for asynchronous work, Redpanda (Kafka-protocol compatible) for event streaming, and Alembic for schema migration. M-Pesa/Daraja is the active payment scope.

**All streaming infrastructure is implemented and tested:**

| # | Capability | Status | Evidence |
|---|-----------|--------|----------|
| 1 | Kafka / Redpanda event backbone | IMPLEMENTED | Redpanda in docker-compose, `topics.py` registry, `KafkaAdminClient` provisioning |
| 2 | Event topics organized by domain | IMPLEMENTED | 30+ domain topics (`pesaguard.<domain>.<stage>`), `EVENT_TYPE_TOPICS` routing |
| 3 | Partition keys and ordering strategy | IMPLEMENTED | `partitioning.event_partition_key()` — `tenant:provider:aggregate` |
| 4 | Consumer groups and independent consumers | IMPLEMENTED | `event_consumer.ConsumerGroup`, 7 default groups, 4 real-time worker stages |
| 5 | Event replay and offset management | IMPLEMENTED | `operations/replay_kafka_range.py`, `EventDeliveryController.replay()`, DLQ replay |
| 6 | Schema registry and compatibility rules | IMPLEMENTED | `schema_validation.py` (JSON Schema), `schema_compatibility.py` (backward compat) |
| 7 | Late-arriving event handling | IMPLEMENTED | `reconciliation_engine` flags `late_arriving_event`, `test_streaming_alignment.py` |
| 8 | Event-time processing | IMPLEMENTED | `EVENT_ENVELOPE_SCHEMA.occurred_at`, fraud detection latency from `event_time` |
| 9 | Backpressure monitoring | IMPLEMENTED | `event_bus.BackpressureGate` (max_in_flight, max_lag) |
| 10 | Consumer lag alerts | IMPLEMENTED | `pesaguard_kafka_consumer_lag_max` metric, `PesaGuardKafkaConsumerLagHigh` alert |

**Test coverage:** 74 tests pass across `test_phase4_event_bus.py`, `test_realtime_worker.py`, `test_streaming_alignment.py`, `test_schema_registry.py`, `test_schema_compatibility.py`, `test_contract_versioning.py`, `test_algorithm_registry_real.py`, and `test_streaming_production_readiness.py` (new production-readiness certification suite).

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


## Stream Processing

PesaGuard implements a complete real-time stream processing pipeline built on Redpanda/Kafka. All 9 stream processing capabilities are implemented and tested:

| # | Capability | Status | Evidence |
|---|-----------|--------|----------|
| 1 | Real-time transaction enrichment | IMPLEMENTED | `enrichment_service.py`, `realtime_pipeline.py` enrich stage |
| 2 | Streaming reconciliation | IMPLEMENTED | `stream_processing.py` `StreamingReconciliation` class |
| 3 | Event-time window aggregation | IMPLEMENTED | `stream_processing.py` `WindowAggregator` (tumbling windows) |
| 4 | Stateful duplicate detection | IMPLEMENTED | `stream_processing.py` `DuplicateDetector` (TTL windows) |
| 5 | Continuous anomaly indicators | IMPLEMENTED | `stream_processing.py` `ContinuousAnomalyMonitor` |
| 6 | Late-event corrections | IMPLEMENTED | `stream_processing.py` `LateEventCorrector` |
| 7 | Checkpointing and recovery | IMPLEMENTED | `stream_processing.py` `CheckpointStore` |
| 8 | Replay-safe processing | IMPLEMENTED | `stream_processing.py` `ReplaySafeProcessor`, idempotent dispatch |
| 9 | Stream-versus-batch consistency | IMPLEMENTED | `stream_processing.py` `check_stream_batch_consistency` |

**Test coverage:** 20 tests in `tests/test_stream_processing_readiness.py` plus 17 tests in `test_realtime_pipeline.py`, `test_realtime_worker.py`, `test_enrichment_service.py`, and `test_phase4_event_bus.py` — all passing.

## Event Topics

| Topic | Partitions | Retention | Purpose |
|-------|-----------|-----------|---------|
| `pesaguard.transactions.raw` | 6 | 7 days | Raw M-Pesa callbacks |
| `pesaguard.transactions.validated` | 6 | 7 days | Validated transactions |
| `pesaguard.transactions.normalized` | 6 | 7 days | Normalized transactions |
| `pesaguard.transactions.enriched` | 3 | 30 days | Enriched transactions |
| `pesaguard.transactions.processed` | 6 | 30 days | Processed transactions |
| `pesaguard.transactions.completed` | 6 | 90 days | Terminal outcomes |
| `pesaguard.transactions.failed` | 3 | 90 days | Failed transactions |
| `pesaguard.transactions.rejected` | 3 | 90 days | Rejected transactions |
| `pesaguard.reconciliation.requested` | 3 | 30 days | Reconciliation requests |
| `pesaguard.reconciliation.completed` | 3 | 30 days | Reconciliation results |
| `pesaguard.reconciliation.exceptions` | 3 | 90 days | Reconciliation exceptions |
| `pesaguard.notifications.requested` | 3 | 30 days | Notification requests |
| `pesaguard.notifications.sent` | 3 | 30 days | Sent notifications |
| `pesaguard.notifications.failed` | 3 | 30 days | Failed notifications |
| `pesaguard.fraud.analysis` | 3 | 30 days | Fraud analysis |
| `pesaguard.fraud.anomalies` | 3 | 30 days | Fraud anomalies |
| `pesaguard.fraud.decisions` | 3 | 30 days | Fraud decisions |
| `pesaguard.webhooks.received` | 3 | 30 days | Webhook events |
| `pesaguard.batch.imports` | 3 | 30 days | Batch imports |
| `pesaguard.audit.events` | 3 | 365 days | Audit events |
| `pesaguard.dlq` | 3 | 30 days | Dead letter queue |

## Consumer Groups

| Consumer Group | Topics | Purpose |
|---------------|--------|---------|
| `transaction-validation` | `pesaguard.transactions.raw` | Validate incoming transactions |
| `transaction-normalization` | `pesaguard.transactions.validated` | Normalize transactions |
| `transaction-enrichment` | `pesaguard.transactions.normalized` | Enrich with merchant/customer/risk context |
| `transaction-processors` | `pesaguard.transactions.enriched` | Process enriched transactions |
| `fraud` | Multiple fraud topics | Fraud detection and scoring |
| `audit` | Multiple audit topics | Audit event processing |
| `alerts` | Multiple alert topics | Alert generation |

## Processing Guarantees

- **Idempotency:** All stream processing is idempotent — duplicate events return the same result
- **Replay safety:** Events can be replayed from the DLQ without side effects
- **Tenant isolation:** All processing is scoped by tenant_id
- **Event-time processing:** Processing uses event timestamps (`occurred_at`), not processing time
- **Backpressure:** `BackpressureGate` bounds in-flight work based on consumer lag

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
