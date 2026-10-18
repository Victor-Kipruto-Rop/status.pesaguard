# Reliability & Performance (Phase 20)

Status: **AUDIT ONLY. NO CODE CHANGED, NO LOAD TEST RUN.**

The honest headline: **most of what this phase asks to be tested does not
exist.** Load-testing a queue that was never built, or measuring consumer lag on
a broker that is not installed, would produce numbers that look like verification
and mean nothing. Section "What could not be verified" lists this precisely.

Build unchanged this phase: **598 tests, 0 failures.**

## Established facts

Measured from the repository, 336 Java files:

| Property | State |
|---|---|
| Redis | **Absent** — no dependency, no container, no config |
| Redpanda / Kafka | **Absent** — no dependency, no container |
| Outbound HTTP clients | **Zero** — no `RestClient`, `WebClient`, `HttpClient`, or `URLConnection` |
| Webhook package | `RetryPolicy` + `WebhookSignature` only. No dispatcher, no sender |
| Caching | **Absent** — no `@Cacheable`, no `CacheManager`. 4 ad-hoc in-memory structures |
| Scheduled work | 4 methods, all in `UsageRollupScheduler` |
| DLQ | A status value (`DEAD_LETTERED`) and an index in V11. **No writer** |
| `open-in-view` | `false` — correct |

## What is already correct

**Stateless instances.** `SessionCreationPolicy.STATELESS` on both filter chains,
`httpBasic` and `formLogin` disabled, CSRF disabled, no `HttpSession` use. Nothing
is held in the session, so instances scale horizontally with no sticky routing.

**`open-in-view: false`.** Set correctly and consistently. Lazy loading past the
transaction boundary is the most common source of N+1 queries in Spring Boot, and
this is switched off at the source rather than case by case.

**No N+1 found.** A scan for query-triggering accessors inside loops produced only
false positives — `EventSubscription.encodeFilters` iterates an in-memory `Map`,
and `UsageQueryService` maps already-fetched entities in memory. Entities are
loaded in bulk and aggregated in Java, which is the right pattern for this data
volume.

**Retry logic is pure and testable.** `RetryPolicy` (webhooks) and
`NotificationRetryPolicy` are pure functions with injected jitter, both with
overflow-safe ceiling arithmetic, both tested.

**Idempotency is enforced in the database.** Unique constraints rather than
read-then-write checks: `api_request_events.request_id`, `usage_buckets`
per-window key, `security_events` dedup index.

## The one real quantitative finding

```
maximum-pool-size: ${DATABASE_POOL_SIZE:10}
minimum-idle:      ${DATABASE_POOL_MIN_IDLE:2}
connection-timeout: 3000
```

There are **151 `@Transactional` methods**. Every one of those can hold a
connection for its duration, and the pool is the hard ceiling on throughput.

The arithmetic: PostgreSQL defaults to `max_connections = 100`. At 10 connections
per instance, **ten instances exhaust the database's connection budget**, and the
eleventh cannot open a connection at all — `connection-timeout: 3000` then means
requests fail after three seconds rather than degrading gracefully.

Two consequences worth acting on before scaling out:

1. **The pool size must be derived from the database budget**, not chosen
   independently. `DATABASE_POOL_SIZE` is configurable, but nothing checks it
   against the server limit, and the default of 10 is a per-instance figure that
   is easy to forget to divide.
2. **`leak-detection-threshold` is not set.** A connection held past its
   transaction — for example by the audit write path, which performs three queries
   (organization lookup, previous-hash lookup, insert) inside one transaction on
   every security-sensitive action — is currently invisible until it manifests as
   pool exhaustion. HikariCP will log the offending stack trace for free.

Neither change was made here. Sizing depends on the real database limit and
request latency, neither of which can be measured without a running system, and
guessing would be worse than leaving a documented default.

## What could not be verified, and why

| Asked for | Why not |
|---|---|
| Review query plans | No PostgreSQL available. `EXPLAIN` requires a live database. |
| Connection utilization | Requires load against a running deployment. |
| Locking / transaction duration | Same. Static review found no obvious long-running external call inside a transaction, but that is not measurement. |
| Concurrent rate limiting (Redis) | Redis is not installed. The in-memory store is single-node by design and already documented as such. |
| Revocation checks at scale | Needs Redis or a load test. |
| Caching | There is no cache to test. |
| Idempotency at scale | The constraint is correct; throughput is unmeasured. |
| Event throughput / consumer lag | No Redpanda, no broker, no consumer. |
| Retries / DLQ / replay | `RetryPolicy` is unit-tested as pure logic. DLQ has a status and an index but **no producer**, so there is nothing to observe. |
| Webhook delivery load test | **No delivery code exists.** There is no HTTP client, dispatcher, or queue in the codebase. |

Fabricating results for any of these would be worse than reporting the gap,
because a "verified" number that was never measured is indistinguishable from a
real one once it is in a document.

## Performance risks that are structural, not tuning

These will matter at scale and none is fixable by configuration:

1. **The audit write path is three queries per event, in one transaction.** Every
   security-sensitive action pays for an organization lookup, a previous-hash
   lookup, and an insert while holding a connection. At scale this is the most
   likely connection-pressure source in the codebase.
2. **Webhook delivery is synchronous and does not exist yet.** When built, doing
   it inline would put a third-party HTTP call inside the request path — the exact
   pattern the Phase 12 usage recorder was designed to avoid. It must ship as an
   out-of-process worker with a real queue.
3. **The rate limiter is in-memory.** Correct for one instance; on N instances the
   effective limit is N times the configured value. This was a deliberate,
   documented trade-off, and it is the first thing that must change before
   horizontal scaling.
4. **`AuditEvent.append` calls `saveAndFlush`** inside a transaction, forcing a
   round trip per event rather than deferring to commit.

## Recommended sequence

In dependency order, not priority order:

1. Add `leak-detection-threshold` and derive pool size from the real database
   connection budget. Cheap, and it makes the next steps measurable.
2. Measure audit write latency under load before tuning anything else.
3. Build the webhook dispatcher as a queue-backed worker — not inline — then
   measure its throughput and queue depth.
4. Introduce a distributed counter store before scaling beyond one instance, or
   accept that limits are per-instance and document it to customers.
5. Only then revisit caching; adding a cache before measuring tends to hide the
   underlying query cost rather than fix it.