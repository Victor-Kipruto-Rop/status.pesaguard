# Reconciliation Workflow

> Design and operating reference for **Product / Reconciliation Workflow**.

## Purpose

This document defines the role of **Reconciliation Workflow** within PesaGuard. It provides a durable reference for customer outcomes, scope boundaries, workflows, roles, and product decisions, with enough detail for implementation, review, operations, and future change.

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

## Validation

At minimum, validate syntax and imports, focused unit behavior, cross-tenant isolation, migration compatibility, API contract behavior, and failure/retry paths. For integration changes, run the PostgreSQL, Redis, and Kafka checks when those services are available and record unavailable dependencies explicitly.

## Financial matching and control totals

`ReconciliationEngine` version `phase3.3` is the shared Decimal-based matching
authority used by the reconciliation worker and batch reconciliation:

- Transaction identifiers and business references are uppercased with whitespace
  removed. Ledger row identifiers (`internal_ref`) identify individual entries;
  the optional ledger `reference` identifies their shared payment/invoice reference.
- Amounts are validated and quantized using the currency's minor-unit precision.
  Missing currency uses the M-Pesa default (`KES`); supported input aliases
  normalize to ISO currency codes. Explicit unsupported currencies and malformed
  supplied timestamps are exceptions, not silently coerced values. Daraja
  timestamps without a timezone are interpreted as UTC.
- A payment may match multiple ledger rows only when all rows have the same
  normalized reference and currency, every timestamp is within the configured
  window, the row identifiers are unique, and their Decimal sum exactly equals
  the payment. The inverse many-to-one cardinality is also supported only when
  distinct payments share the same normalized reference and currency, fall within
  the configured window, and sum exactly to one ledger row. Under-total
  many-to-one groups are `PARTIAL`; over-total groups are `MISMATCH`. Non-exact
  groups do not consume ledger rows.
- A unique highest-scoring candidate may be selected. Equal top scores are
  ambiguous: the engine returns `EXCEPTION` and the legacy evaluator returns
  `needs_review`, with stable candidate references in evidence rather than an
  arbitrary financial match. Duplicate payment IDs are `DUPLICATE`; missing
  candidates are `UNMATCHED`. A batch with duplicate normalized ledger row IDs is
  rejected during input validation.
- `reconcile_batch` accepts at most 5,000 payment events and 5,000 ledger rows.
  It sorts inputs and matching candidates deterministically, rejects conflicting
  reuse of a payment ID and duplicate normalized ledger row IDs, counts each
  payment ID once in control totals, rejects explicitly mixed-tenant batches,
  and allocates a ledger row to at most one exact match. It returns per-currency
  payment/ledger totals, signed
  payment-minus-ledger differences, matched/unmatched counts, unmatched ledger
  rows, and per-event evidence.
- The worker requests a bounded candidate set and atomically persists each
  outcome, versioned rules and full evidence in the append-only audit,
  `ReconciliationMatch`, and outbox.
  Candidate currency and tolerance follow the same ISO alias and minor-unit rules
  as the matching engine. Candidate overflow and ledger connector failures raise
  rather than silently omitting rows or reporting a false empty ledger.
- Transaction and reconciliation lifecycle edges are enforced in application
  logic and by database triggers. Established `ReconciliationMatch` decisions
  cannot be updated or deleted; replaying a completed transaction is a duplicate.
  There is no authorized correction workflow today, so reprocessing cannot
  overwrite an established outcome. Refunds and reversals are separate
  transactions referencing the original record and do not initiate money
  movement.

Postgres ledgers that contain split entries must map both the row identifier and
the shared reference (and should map currency where the ledger stores it). For
example:

```json
{
  "internal_ref": "entry_id",
  "reference": "invoice_ref",
  "amount": "amount",
  "phone_number": "phone",
  "timestamp": "created_at",
  "status": "status",
  "currency": "currency"
}
```

Without the shared-reference mapping, a connector cannot reliably fetch every
component row for grouped reconciliation. Map currency when the ledger stores
non-default currencies; otherwise absent currency is interpreted as KES. Connector
candidate queries currently use the configured recent-record horizon; replaying
an old payment event does not itself widen that ledger query, so historical
backfills must provide ledger rows from the matching period.

`SettlementReportingService` returns tenant-scoped settlement calculation and
variance summaries with monetary values serialized as strings. The settlement
calculation/reconciliation/reporting services are not currently wired to HTTP or
worker entry points; neither reconciliation nor settlement summaries imply
provider confirmation or fund movement.

### Verification and deployment limits

The final focused local run covered transaction-ingestion hardening and
migrations, API and batch ingestion, tenant isolation, Daraja validation and
signatures, reconciliation (including grouped financial processing,
determinism, state, dead-letter, audit-immutability, and connectors), and
settlement: **147 passed, 11 warnings in 104.76 seconds** on Python 3.13.
The coverage run reported 77% for `base_connector.py`, 83% for
`reconciliation_engine.py`, and 64% for `reconciliation_job.py` (75% across
those measured modules). `validators.py` and `event_store.py` were already
imported before coverage instrumentation and were not measured; changed-line
coverage for those modules remains unverified. Warnings were SQLAlchemy SQLite
datetime-adapter and test `utcnow()` deprecations.

The reconciliation engine's local synthetic 250-payment / 250-ledger benchmark
ran on Python 3.13 in 0.307 seconds after indexing reference and amount
candidates, compared with 1.863 seconds before indexing in the same environment.
This is a small local microbenchmark, not representative production capacity
evidence or an SLO result.

Postgres candidate normalization and Decimal query behavior still require a
target-like PostgreSQL integration run; current connector regression coverage
uses SQLite. Redis/Kafka outage recovery, historical backfill behavior against
production ledger data, deployment capacity, and alert thresholds also remain
unverified. The lifecycle and immutable-decision triggers are covered by
isolated SQLite migration tests, but the PostgreSQL migration has not been run.
The full SQLite Alembic chain remains blocked by an earlier migration that uses
PostgreSQL-only `md5()`. Reverting the matching code leaves previously persisted
reconciliation evidence intact; trigger downgrade removes the new database
guards.

The 2026-10-14 focused reconciliation, group-matching, batch-delivery,
dead-letter, audit, lifecycle, and database-constraint run passed **124 tests
with 11 warnings**. Alembic resolves to one head,
`20261014_quarantine_remediation_history`, downstream of the lifecycle-guard
migration. This verifies revision ancestry, not a production PostgreSQL upgrade.

## Open work

- Run target-like PostgreSQL, Redis, and Kafka integration/recovery checks before
  claiming production readiness, and benchmark representative tenant data against
  an approved capacity target.
- Decide whether to expose settlement summaries through a tenant-authorized HTTP
  reporting contract; the current service-layer reporting class is not routed.
- Review this document whenever the public contract, ownership boundary, or
  recovery behavior changes.
