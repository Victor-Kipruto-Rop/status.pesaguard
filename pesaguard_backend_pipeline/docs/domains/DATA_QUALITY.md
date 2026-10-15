# Transaction data quality

## Scope and ownership

The transaction data-quality gate runs before durable M-Pesa transaction and
outbox writes, inside the event-store database transaction. A rejected record is
stored in the tenant-scoped quarantine table and does not create a normal
transaction, processed-transaction marker, or transaction outbox message.
Identical retry payloads resolve to the existing quarantine record. Accepted
records persist provenance and data-quality evidence in the same transaction as
the transaction itself. Database failure rolls back the whole decision and asks
the caller to retry; it must not acknowledge a record whose disposition is not
durable.

The gate is wired into both the M-Pesa/Daraja `IngestionService` path and the
authenticated `/api/v1/transactions` write route. `run_data_quality`,
`run_data_quality_batch`, `dq_report`, and `dq_batch_report` are service-level
APIs; no tenant-facing report or quarantine-management HTTP route is exposed by
this change. A quarantined write returns `202` with a quarantined status; it
does not report an accepted transaction.

## Checks

Blocking checks cover transaction ID presence, positive currency-normalized
amounts, supported currency and provider, tenant and schema version, supplied
reference correctness, timestamp presence/parseability/future skew, conflicting
amount aliases, and known schema compatibility. A missing payment reference is
allowed when the source contract does not supply one; a supplied empty reference
fails. Unsupported/incompatible schema versions fail closed. A supported
`migration_required` version is accepted with a warning.

Batch and scoped historical comparisons detect repeated transaction IDs within
the `(tenant, provider)` scope. Identical duplicates are warnings; reuse of an
ID with a different payload hash is a blocking failure. The ingestion adapter
checks existing amount peers from the same tenant, provider, and currency only.
Outliers use the modified Z-score (`0.6745 * abs(amount - median) / MAD`) against
at least 20 valid peers and the default cutoff 3.5. A zero MAD falls back to
1.5-IQR fences, or flags any amount unlike a constant peer group. Outlier
findings reduce the accuracy score and are warnings; they do not quarantine
otherwise-valid events.

Freshness uses event time relative to a timezone-aware processing time. Invalid
timestamps and events more than five minutes in the future fail validation.
When `PESAGUARD_DQ_MAX_EVENT_AGE_SECONDS` is set to a positive finite number,
older events are reported as stale warnings and lower the timeliness score, but
are not quarantined. The variable is blank by default so historical imports are
not rejected. Age and status remain in the record profile when no threshold is
configured.

## Scoring

The versioned formula is `equal_dimension_rule_mean_v1`:

1. Each dimension score is the number of passed applicable checks divided by the
   number of applicable checks in that dimension, rounded to four decimal
   places.
2. Non-blocking warnings count as failed checks in their dimension, so
   outliers, stale events, migration-required schemas, and identical duplicates
   lower the relevant score without changing an accepted decision.
3. A dimension with no applicable checks scores 1.0; its report retains check
   applicability so this neutral score is distinguishable from measured passes.
4. Overall score is the arithmetic mean of the six dimension scores, rounded to
   four decimal places. The six dimensions have equal weight.

Reports include formulas/version, per-check applicability, pass/blocking
decisions, warnings, failures, null/empty fields, freshness profile, record
counts, duplicate counts, and batch warning summaries. Batch inputs and peer
sets are bounded to 5,000 records.

## Quarantine and remediation history

Quarantine payloads and hashes are tenant-scoped and deduplicated by tenant,
provider, and SHA-256 payload hash. `record_quarantine_remediation` requires the
tenant and actor from trusted caller context, locks the tenant-owned quarantine
row, validates the allowed state transition, and appends an immutable history
record. Corrections store a canonical evidence hash, not a second plaintext
copy of the payload. History retrieval first verifies quarantine ownership.
Remediation helpers do not themselves replay or reinject corrected records.
Those actions require a separately authorized operational workflow.

The Alembic migration adds persistent checks and score fields, the scoped peer
index, quarantine deduplication, composite tenant foreign key, and immutable
remediation history. Downgrade refuses to drop non-empty remediation history;
archive that evidence before rolling back. Existing DQ data receives a safe
neutral score default and an empty check list because prior check-level evidence
was not persisted.

## Verification

The focused local regression selection most recently passed **76 tests**;
one unrelated auth-alias test was deselected because this environment returns
503 when its Redis-backed dependency is unavailable. It covered quality
scoring/report shape, freshness, schema contracts, persistence, quarantine,
tenant boundaries, migrations, and the authenticated transaction write route.

## Verification limits

SQLite integration tests verify the gate, idempotent quarantine, tenant
separation, remediation transitions, append-only database enforcement, and
migration reversal behavior. Production PostgreSQL migration execution,
concurrent quarantine/remediation races, representative high-volume peer-query
capacity, outlier-baseline calibration, and alert thresholds require target-like
verification. No data-quality or deployment certification is implied by local
tests.
