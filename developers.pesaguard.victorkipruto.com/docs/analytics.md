# API Usage & Request Analytics (Phase 12)

Status: **WIRED END TO END, NOT YET RUN AGAINST A DATABASE.** Ingestion, rollup,
and the read API are all implemented and unit-tested. V12 has never been executed
(Docker/PostgreSQL unavailable), so no usage data has actually been collected and
every schema-level claim below is unverified.

## What exists

| Component | File | State |
|---|---|---|
| Granularity + windowing | `analytics/domain/UsageGranularity.java` | Complete, tested |
| Raw request event | `analytics/domain/ApiRequestEvent.java` | Complete, tested |
| Usage bucket | `analytics/domain/UsageBucket.java` | Complete, tested |
| Aggregator | `analytics/application/UsageAggregator.java` | Complete, tested |
| Async buffer | `analytics/application/UsageEventRecorder.java` | Complete, tested |
| Persistence + dedup | `analytics/application/UsageEventPersistenceService.java` | Complete, unverified |
| Rollup | `analytics/application/UsageAggregationService.java` | Complete, unverified |
| Scheduler | `analytics/application/UsageRollupScheduler.java` | Complete, unverified |
| Query service | `analytics/application/UsageQueryService.java` | Complete, tested |
| Tracking filter | `analytics/infrastructure/UsageTrackingFilter.java` | Complete, unverified |
| Attribution | `analytics/infrastructure/RequestAttribution.java` | Complete, unverified |
| Controller | `analytics/api/UsageController.java` | Complete, unverified |
| Schema | `V12__api_usage_analytics.sql` | Written, **not yet executed** |
| Request search and organization-scoped request detail | `analytics/application/RequestObservabilityService.java` | Implemented; PostgreSQL validation pending |

## How a request becomes a number

```
request
  -> UsageTrackingFilter      (every request; measures, never blocks)
  -> RequestAttribution       (who owns it: org/project/env/credential)
  -> UsageEventRecorder       (bounded in-memory queue, non-blocking)
  -- background thread -->
  -> UsageEventPersistenceService   (one transaction per event; duplicates rejected)
  -> api_request_events            (append-only, request_id UNIQUE)
  -- UsageRollupScheduler, on cron -->
  -> UsageAggregationService        (recompute from raw, per window)
  -> usage_buckets
  -- GET /api/v1/usage -->
  -> UsageQueryService -> UsageController
```

`GET /api/v1/usage/requests` exposes a bounded, paginated view of persisted
request metadata (default seven-day range, maximum 31 days and 100 rows per
page). Optional filters include project, environment, status, method and exact
request ID. `GET /api/v1/usage/requests/{requestId}` looks up one request inside
the authenticated organization. Both endpoints require `usage:read`.

An API key with `usage:read` may call `GET /api/v1/key-data/usage`. Its project
and environment filters are taken from the key itself, not the query string, so
this read-only data route cannot be widened by changing request parameters.

These are request records, not distributed spans. The response omits user IDs,
credential IDs, headers, request bodies and response bodies; it includes only
fields that `api_request_events` actually records.

## Request tracking

`ApiRequestEvent` captures every field the phase requires: request id,
organization, project, environment, API key, OAuth application, endpoint, method,
status, latency, timestamp, and response size, plus the user id.

Status classification:

- **Successful** — 2xx
- **Client error** — 4xx
- **Server error** — 5xx
- **Failure** — anything >= 400

1xx and 3xx are deliberately *not* failures. A `304 Not Modified` is a correct
answer; counting it as an error would make the error rate meaningless.

### Two decisions that protect the payment API

**Recording never blocks and never throws.** It runs outside any transaction and
only enqueues. A synchronous insert would add a database round trip to every API
call, and a database blip would turn an analytics outage into a payment outage.
An observability subsystem that can take down the system it observes is worse
than no observability at all, so every failure path in the filter logs and
returns.

**The buffer is bounded.** An unbounded queue grows until the heap is exhausted
and takes the payment API down with it. On overflow the event is **dropped and
counted** (`UsageEventRecorder.droppedCount()`), never silently discarded. A
non-zero count means usage data is incomplete and someone should look — this
metric needs monitoring, and nothing currently alerts on it.

Query strings are never stored: they carry tokens, emails, and search terms, and
the `endpoint` column is grouped on, so a per-request query value would also
explode the bucket count. Health and metrics endpoints are excluded.

### Attribution and tenant isolation

The organization comes from the authenticated security context, never from a
request parameter, query string, or path segment. A request that cannot be
attributed is not stored at all — `organization_id` is `NOT NULL` precisely so an
unowned row can never appear in a tenant's totals.

**Known gap:** `api_key_id` and `oauth_application_id` are always `null` today.
`ApiKeyAuthenticator` exists but is not invoked by any filter, so there are no
live API-key requests to attribute. The schema, the domain, and the rollups all
carry the column; it is populated at the single point where the credential
filter will land (`RequestAttribution.DefaultResolver`).

**Known limitation — retry dedup.** `CorrelationIdFilter` *generates* a UUID
when `X-Request-ID` is absent, so a retry from a client that does not send a
stable id gets a different id and is counted separately. Deduplication works
only for clients that send `X-Request-ID`. This is a property of HTTP retries
rather than a server-side oversight: two identical requests are
indistinguishable from two genuine ones.


## Analytics available

Once rolled up, a bucket answers: request volume, successful requests, failed
requests, error rate, latency (sum/max/mean and p50/p95/p99), response bytes, and
counts per endpoint, per environment, and per credential. The dimensions are
carried on the bucket, so endpoint, environment, and credential usage are all
direct queries rather than separate tables.

## The three failure modes, and how each is handled

These are the parts of the phase most likely to produce quietly wrong numbers.

### Duplicates

`api_request_events.request_id` is `UNIQUE`. A client retry, a proxy replay, or a
double-charged delivery presents the same request id and is **rejected at the
database** rather than inflating the customer's usage.

This is deliberately the database's job and not the application's. A check in
application code has a race between two concurrent ingestion threads; a unique
constraint does not.

### Late events

An event is assigned to a bucket by `occurred_at`, never by `recorded_at`. A
backlog flushed after midnight is counted against the day the requests actually
happened.

A window is only closed once it is both **in the past** and older than a lateness
allowance (`UsageAggregator.isWindowClosed`). Closing eagerly would roll up a
window and then have to correct it when a late event arrived.

Late events are not discarded and do not corrupt the bucket: they are re-aggregated
into the already-closed window and counted in `late_event_count`, so an operator
can distinguish "this bucket is complete" from "complete and then adjusted".

### Aggregation failures

**Rollups recompute; they never increment.** `UsageBucket.replaceAggregates` sets
absolute values computed from the raw events for the window.

This is the property that makes failure recovery trivial: a rollup that ran
halfway and died is fixed by **running it again**. An additive counter would
double every figure on the second run and there would be no cheap way to tell a
correct total from a doubled one.

Both behaviours are asserted directly in `UsageAggregatorTest`
(`applyingTwiceToABucketDoesNotDoubleCount`, `recomputationCorrectsEarlierIncompleteTotals`).

The supporting measure is that `api_request_events` is **append-only** — a trigger
rejects updates and deletes. The recompute strategy is only safe while the raw

## Aggregation ladder

`MINUTE -> HOUR -> DAY -> MONTH`, exposed as `UsageGranularity.parent()`.

Two details that are easy to get wrong and were caught by tests here:

- **Truncation is floor, not round, and always in UTC.** Rounding would place an
  event in a window that has not closed yet, where the later rollup would
  double-count it. Local-time truncation would put the same instant in different
  buckets depending on which node ran it. Windows are half-open `[start, end)` so
  an instant on a boundary belongs to exactly one window.
- **`Instant` cannot be truncated to months.** `Instant.truncatedTo(ChronoUnit.MONTHS)`
  throws `UnsupportedTemporalTypeException`, and `Instant.plus(1, MONTHS)` likewise.
  Every `MONTH` bucket would have failed at runtime. Months go through
  `LocalDate`/`plusMonths` instead, which also handles December and leap years
  correctly where naive 30-day arithmetic would drift.

`MONTH` has no parent — rolling it into itself would double-count.

## Percentiles

Nearest-rank, not interpolated. A latency percentile should be a latency some
request actually measured; interpolating between two samples invents a number
nobody observed, which matters when an operator uses it to decide whether to
raise a timeout.

## Not implemented

- **No OpenAPI route.** `/api/v1/usage` and `/api/v1/usage/endpoints` exist
  in code but are absent from `pesaguard-developer-v1.yaml`.
- **No integration tests.** Everything below the controller is unverified against
  a real database.
- **No alert on buffer overflow.** `droppedCount()` exists but nothing watches it,
  so silent usage loss would go unnoticed.
- **No credential attribution.** See the known gap above.
- **V12 has never been executed.** Docker and PostgreSQL are unavailable here, so
  the unique index, partition check, and append-only trigger are unverified, and
  the Hibernate mappings have never been validated. Treat the schema as
  unreviewed until it has run.
- **No raw-event retention policy.** Raw events are never pruned, which is
  required for recompute but means unbounded growth.
- **No data-quality monitoring** on ingestion completeness.

## Design decisions worth revisiting

- **Buckets are per (endpoint, method, credential).** An organization-wide total
  is a sum over many rows rather than a single read. That made endpoint and
  credential usage direct lookups, but the common "total traffic" question became
  the expensive one. The alternative is a second, dimension-less bucket.
- **A corrected bucket keeps its original aggregates**; `late_event_count` records
  the adjustment rather than restating the counts. Current numbers are correct
  and the fact they were revised is retained, but nothing exposes the late count
  through the API yet.
- **Overload drops usage rather than slowing requests.** For usage feeding a bill,
  under-counting is arguably worse than added latency. The alternative is a
  durable queue, which trades a payment-path risk for operational complexity.
  Worth revisiting before this feeds invoicing.
- **Ingestion failures are counted, not quarantined.** A failed write is logged and
  dropped. A DLQ or retry table would preserve it for later recovery.
- **The scheduler is not clustered-safe.** Two nodes will both roll up the same
  windows. This is harmless because rollups recompute, but it doubles the load.
  Worth a leader lock or a single-node deployment before scaling out.
