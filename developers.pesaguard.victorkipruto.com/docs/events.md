# Event Registry and Subscriptions

The event registry, tenant-scoped catalog and stream APIs, subscription
management, transactional project and credential event emission, and webhook
dispatch are implemented. Webhook dispatch reads committed outbox rows directly;
it does not wait for the optional Kafka publisher to mark an event published.

## What is built

| Component | State |
| --- | --- |
| Event type name grammar | **Complete and tested** |
| Registry entry: description, schema, category, version | **Complete and tested** |
| Lifecycle and deprecation with a sunset gate | **Complete and tested** |
| Subscriptions: filters, version, endpoint, status, environment | **Complete and tested** |
| Delivery tracking per attempt | **Complete and tested** |
| `V11` schema and seeded catalog | Present; validate against PostgreSQL before deployment |
| Tenant-scoped services, repositories and controllers | **Implemented** |
| Scheduled dispatch from committed outbox records | **Implemented** |
| Project and API-key lifecycle event emission | **Implemented** |
| Webhook creation and production-access lifecycle event emission | **Implemented** |
| Required-field validation against the registered schema | **Implemented** |

## Event names

Names are `namespace.entity.action`, or `namespace.entity.path.action` for a
nested entity. The namespace is the first segment and the action is always the
last.

```
developer.project.created
developer.api_key.revoked
developer.webhook.delivery.failed     <- nested: entity is webhook.delivery
```

Segments are lowercase with hyphens and underscores, 2–32 characters. Three
segments minimum, four maximum.

### The grammar is a security control

Event names reach delivery headers, log lines and metric labels. A name carrying
a newline, a space or a quote is a log-injection and metric-poisoning hazard, so
the grammar rejects all of them, along with uppercase (never silently folded) and
the `*` wildcard, which is a subscription-filter concept rather than a name.

### This grammar was wrong twice

Both were caught by tests, and both would have shipped a registry that rejected
## Lifecycle and deprecation

```
DRAFT --activate--> ACTIVE --deprecate--> DEPRECATED --retire--> RETIRED
```

| State | Emitted | Notes |
| --- | --- | --- |
| `DRAFT` | no | Registered but not yet emitted |
| `ACTIVE` | yes | |
| `DEPRECATED` | **yes, until sunset** | Subscribers are told what to migrate to |
| `RETIRED` | no | Terminal |

### Deprecation is not removal

A deprecated event keeps being emitted until its sunset date. Silently stopping a
published event breaks every live integration that subscribed to it, and an outage
is not a deprecation strategy.

Deprecating requires a sunset date. A deprecation with no end leaves subscribers
guessing whether the event is still coming.

### Retirement is gated on the date, not on confidence

`retire()` is **refused** before the sunset date. This is the rule that stops an
operator breaking integrations by retiring an event early, however sure they are
that nobody subscribes to it.

Emission stops at sunset even before the retirement sweep runs, so a late or absent
sweep cannot keep an event alive past the date subscribers were promised.

## Versioning

Every event carries a version, and every subscription **pins** one. If
`developer.project.created` moves to v2 by gaining a field, a subscriber pinned to
v1 keeps receiving the v1 shape. Without pinning, a routine additive change
silently alters what integrators parse. The current dispatcher only delivers the
currently registered version; historical schema transformation is not yet
implemented.

## Subscriptions

A subscription names an event, pins its version, targets an endpoint, and is bound
to exactly one project environment. The selected environment is required and must
match the endpoint's environment; null no longer means "every environment".
Event and delivery listings and replay operations require the same project and
environment scope. Events without an environment identifier are not delivered to
environment-bound subscriptions.

Status is `ACTIVE`, `SUSPENDED` or `CANCELLED` (terminal).

### Filters can only narrow

Filter keys are an allowlist: `projectId`, `environmentId`, `resourceType`,
`resourceId`, `status`. All filters must match (AND).

Two rules that matter:

- **`organizationId` is not filterable.** A subscription is created for one tenant
  and no filter can widen that. This is what stops a subscriber reaching another
  organization's events by crafting a filter.
- **A missing key does not match.** If an event lacks a filtered attribute it is
  not delivered, rather than being delivered on an assumed value. A financial
  event delivered to the wrong webhook is not a recoverable mistake.

## Delivery tracking

Every attempt is a row. Attempts are never overwritten, so "how many times did
this fail, and what did the endpoint say each time?" stays answerable.

Tracked per attempt: event ID, subscription ID, delivery ID, attempt number,
timestamp, status, response code, error code, latency, and the next attempt time.

Status is `PENDING`, `IN_FLIGHT`, `DELIVERED`, `RETRY_SCHEDULED`, `FAILED`,
`DEAD_LETTERED` or `SUPPRESSED`.

The table is **append-only** — the database rejects updates and deletes. The DLQ is
evidence that something failed and a person must look; a delivery log that can be
edited is not evidence.

Delivery response bodies are discarded and not persisted.

## Limitations

- Payloads are stored as valid JSON but are not validated against the registered
  JSON Schema before commit or delivery.
- Subscription versions are recorded, but historical payload transformation is
  not implemented; only the current event version is sent.
- Event delivery is at-least-once. Receivers must deduplicate by event ID.
- Dispatchers coordinate across nodes with short-lived database claims. A process
  crash after the receiver accepted the request but before the attempt is recorded
  can still cause redelivery.
- Dead-lettered attempts can be explicitly replayed; automatic retry uses the
  configured tested full-jitter policy.
- PostgreSQL migration and multi-instance dispatcher behavior still require
  integration validation.

## Verification status

- Domain behaviour is covered by tests, including the seeded-catalog guard.
- **NOT TESTED against PostgreSQL.** The check constraints, foreign keys, the
  append-only trigger and the seed are **unverified**. Docker is unavailable in
  this environment.
- **The migration version sequence skips V8.** There is no `V8__*`; Flyway tolerates
  this, but adding one later would be an out-of-order migration and would need
  `outOfOrder` handling or renumbering.
- **Uncommitted** — the working directory is entirely untracked and CI has never
  run.
its own catalog:

1. The first grammar disallowed underscores, so `developer.api_key.created` — one
   of the platform's own event names — was refused.
2. The second required exactly three segments, so `developer.webhook.delivery.failed`
   was refused.

`everySeededEventNameSatisfiesTheGrammar` now asserts every seeded name parses, so
the grammar and the catalog cannot drift apart again.
