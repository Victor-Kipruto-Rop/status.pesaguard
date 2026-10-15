# Database Schema

> Design and operating reference for **Database / Database Schema**.

## Purpose

This document defines the role of **Database Schema** within PesaGuard. It provides a durable reference for durable storage, schema evolution, retention, archival, backup, and restoration, with enough detail for implementation, review, operations, and future change.

## Current position

PesaGuard currently runs a Flask and SQLAlchemy backend with PostgreSQL as the production store, Redis and RQ for asynchronous work, Kafka for event transport, and Alembic for schema migration. M-Pesa/Daraja is the active payment scope. Capabilities described here must be marked as implemented, in progress, or planned rather than implied.

## Financial data layers and ownership

- The PostgreSQL operational schema stores immutable provider source fields
  (`transactions.raw_payload` plus its hash/schema version), lifecycle state,
  tenant-scoped idempotency, append-only `transaction_events`, outbox intent,
  and reconciliation evidence.
- `TransformationRecord`, `LineageRecord`, and `TransactionProvenance` record
  raw/validated/normalized/enriched/processed stage evidence and the schema,
  pipeline, or decision versions associated with that evidence. These are
  logical stages in the operational schema, not separate raw/validated/curated
  PostgreSQL databases. Curated metrics are derived and must not replace source
  or audit evidence.
- `ledgers`, `ledger_accounts`, `ledger_transactions`, and `ledger_entries`
  provide tenant-scoped accounting records. Composite tenant/ledger foreign
  keys prevent cross-tenant or cross-ledger references; balances are derived
  from posted entries, not treated as independently editable source fields.
- Schema changes are Alembic-owned. Do not use `Base.metadata.create_all()` to
  provision production tables. Migration `20261016_double_entry_ledger`
  enables and forces tenant RLS on all four ledger tables and grants the
  runtime role only `SELECT`, `INSERT`, and `UPDATE`.
- Migration `20261017_webhook_runtime_grants` grants the non-bypass runtime
  role CRUD on tenant-scoped webhook configurations and only `SELECT`,
  `INSERT`, and `UPDATE` on delivery history; delivery deletion remains
  unavailable to the application role. These table-specific grants do not
  replace deployment-wide privilege and role-membership verification.
- Migration `20261018_transaction_ingestion_runtime_grants` grants only the
  `SELECT`/`INSERT`/`UPDATE` operations used by durable ingestion on transaction
  state, processing status, and outbox tables. Append-style transaction,
  idempotency, audit, transformation, and lineage evidence is granted only
  `SELECT`/`INSERT`; the runtime role receives no `DELETE` on these records.

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

- Verify migrations and RLS with deployment credentials after rollout; local
  PostgreSQL validation is not evidence about production role membership or
  grants.
- Physical partitioning of the transaction table is intentionally not enabled:
  no representative production volume, query plan, retention benchmark, or
  approved threshold currently justifies its operational cost.
- Production object-store archival, approved retention periods, off-site backup
  durability, restore/PITR drills, and measured RPO/RTO remain deployment
  gates. See [Data Retention](./DATA_RETENTION.md),
  [Data Archival](./DATA_ARCHIVAL.md), and [Backups](./BACKUPS.md).
