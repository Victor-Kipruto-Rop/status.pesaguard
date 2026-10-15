# Event Architecture

## Overview

PesaGuard uses an event-first model even though the application is still structured as a modular monolith. Events represent normalized facts about provider callbacks, reconciliation outcomes, and downstream operator actions.

## Event model

The platform follows a consistent pattern:
- an inbound transaction callback becomes a durable event record
- normalized event data is routed through an idempotent pipeline
- downstream business actions are triggered by the event, not by direct ad hoc calls
- every event carries enough metadata to replay, audit, and explain the outcome

## Event flow

```text
M-Pesa callback
  -> normalized provider event
  -> event store + idempotency check
  -> Kafka or queue dispatch
  -> reconciliation worker
  -> discrepancy / alert producer
  -> notification or reporting consumer
  -> audit trail and observability
```

## Kafka reconciliation delivery and disposition

The reconciliation consumer uses at-least-once delivery. Three states must not
be conflated:

- A **Kafka offset** is a per-partition broker position. Committing offset `n`
  acknowledges that the consumer group may resume at the next position, `n+1`.
- An **application processing result** or metric is local execution state; it is
  not a broker acknowledgement and does not prove that data was durably handled.
- A **durable disposition** is either the committed reconciliation transaction
  (including its idempotency record, audit evidence, and outbox) or a committed,
  tenant-scoped dead-letter record containing the original payload and Kafka
  topic/partition/offset.

The consumer commits a message's next offset only after one of those durable
dispositions succeeds. A malformed record is durably quarantined before it is
acknowledged. If processing and dead-letter persistence both fail, or the
explicit offset commit fails, the consumer pauses that partition, seeks back to
the failed offset, and retries it after a short delay. Other partitions may
continue. If pause/seek cannot be applied, consumption fails visibly rather than
advancing past the unresolved record.

A crash after the database disposition commits but before Kafka confirms the
offset may cause redelivery. Reconciliation idempotency makes an already
completed transaction a no-op; dead-letter persistence is idempotent, so the
same poison record does not create multiple quarantine records. The outbox
preserves downstream publication for successfully reconciled transactions.
Operators must use the recorded tenant and Kafka source coordinates when
investigating or replaying a quarantined record.

## Reconciliation decisions and financial history

The reconciliation engine emits one auditable outcome per payment:
`MATCHED`, `PARTIAL`, `MISMATCH`, `DUPLICATE`, `UNMATCHED`, `PENDING`, or
`EXCEPTION`. A unique highest-scoring candidate may be selected. Equal top
scores are ambiguous and produce `EXCEPTION` (or `needs_review` in the legacy
compatibility evaluator); stable candidate ordering is for evidence only and
never chooses a winner.

One-to-many and many-to-one group matches are automatic only when the
same-reference, same-currency, in-window aggregate equals exactly. A
many-to-one under-total is `PARTIAL`; an over-total is `MISMATCH`. Group
outcomes include the participating transaction IDs, ledger references, rule
version, and aggregate amounts. Non-exact groups do not consume ledger rows.
Duplicate transaction IDs are `DUPLICATE`; duplicate normalized ledger
identifiers reject a batch during input validation; a missing candidate is
`UNMATCHED`.

Every reconciliation persistence requires a recognized outcome and complete
versioned evidence. The outcome, match row, append-only audit evidence, and
outbox intent commit in one database transaction. Replaying a completed
transaction is a no-op. Database triggers enforce transaction and
reconciliation lifecycle edges and reject updates or deletes of established
match decisions. There is no correction workflow today; an established outcome
cannot be overwritten.

Refunds and reversals must identify the original reference and are recorded as
separate transactions. Reconciliation preserves the original transaction and
ledger history; it does not initiate refunds, reversals, or other movement of
funds.

## Monetary representation and currency boundaries

All provider amounts pass through `normalization.py` before scoring or durable
processing. The canonical amount is a positive, finite `Decimal` serialized at
the currency's minor-unit scale. The explicit supported set is KES, TZS, UGX,
USD, EUR, GBP, and BHD: respectively 2, 2, 0, 2, 2, 2, and 3 fractional
digits. This is an application allowlist, not a claim to support every ISO-4217
currency or every payment rail. The active Daraja product scope remains M-Pesa;
currency-aware internal records do not add provider integrations.

Input precision finer than the currency scale is rejected, not rounded.
`ROUND_HALF_UP` applies only when explicitly quantizing derived values.
Scientific notation is accepted when the exact value is in range and at a valid
minor-unit scale. Booleans, malformed numbers, zero/negative transactions,
NaN, infinities, underflow below one minor unit, excess precision, and amounts
outside `NUMERIC(19,3)` bounds are rejected before scoring or storage.

The exact source payload is retained separately from the canonical amount for
audit and dispute investigation. Currency codes are normalized to uppercase.
Reconciliation, baselines, settlement calculations, and merchant metric buckets
must stay within one currency; there is no implicit FX rate or conversion.
Cross-currency matches are classified as exceptions, and aggregates and their
unique keys include currency. Purchase-order and ledger CSV/Excel imports use
the same strict amount normalizer while retaining the original rows for audit.
Notification thresholds are compared only when the event and configured
threshold currencies agree; otherwise the event is not treated as high-value
based on an incomparable amount, and reconciliation exceptions escalate for
critical review.

## Event types

Although not every event type is finalized in code today, the platform is designed around a common taxonomy:

- `transaction.received`: provider callback captured at ingress
- `transaction.normalized`: provider payload converted to internal event model
- `transaction.idempotent`: duplicate or replay state confirmed
- `transaction.reconciled`: business match classification produced
- `transaction.alerted`: mismatch, anomaly, or suspicious event flagged
- `notification.sent`: operator-facing action dispatched
- `audit.recorded`: compliance or operational evidence persisted

## Producer and consumer responsibilities

### Producers
- webhook ingress captures the provider event
- business services create reconciliation or alert events
- queue and Kafka producers emit validated events to downstream consumers

### Consumers
- async worker processes handle reconciliation or alerting tasks
- notification workers publish messages to email or messaging providers
- operational observers consume health and metrics events for monitoring

## Event quality rules

- event payloads must be validated before enqueueing
- event processing must be idempotent and replay-safe
- event versioning should be explicit when contracts evolve
- event records must not contain raw secret material
- every event must carry correlation metadata and tenant context

## Tenant context and durable database isolation

Tenant identity is established from a verified request principal or a trusted
internal job/event boundary; it is not selected by an unsigned request field.
The database transaction-local `app.tenant_id` GUC and forced PostgreSQL RLS
policies are the final isolation boundary. Tenant-aware outbox workers enumerate
tenant IDs through a dedicated registry-only role and process business rows
under one tenant scope at a time. Missing tenant identity is rejected or
quarantined in a reserved operational bucket, never silently assigned to a
customer tenant. Role provisioning, RLS deployment requirements, provider
callback trust, and the opt-in PostgreSQL verification procedure are documented
in [TENANT_ISOLATION.md](../operations/TENANT_ISOLATION.md).

## Contract ownership

Every event type in this taxonomy has a named producer, contract owner, technical
owner, responsible team, approval process, escalation path, and compatibility
guarantee. The authoritative registry is
[CONTRACT_OWNERSHIP.md](CONTRACT_OWNERSHIP.md); add or change an event type only by
updating that registry in the same change.

## Why this matters

In a financial workflow, the system must be able to answer: what happened, when, why, and under which tenant context? Event architecture provides that traceability without overengineering the platform into a distributed system prematurely.
