# Messaging, Redis, and Workers

## Observed design

- Redpanda runs as a single broker with `--smp=1`, an explicit 1 GiB memory
  argument, and a named data volume. No replicated broker, broker failover, or
  production retention evidence was found.
- The reconciliation consumer sets `enable_auto_commit=False`, processes bounded
  batches, and commits each message offset after successful processing. Persistence
  failures leave the offset uncommitted for retry.
- The RQ background task code configures job timeouts and up to three retries with
  intervals `[10, 30, 60]` for applicable jobs. There is outbox/dead-letter support
  in source.
- Redis 7 is a Compose dependency for the RQ worker and has no explicit persistent
  volume in the base Compose file.
- Communications outbox and retry/dead-letter behavior are represented in code
  and schema, but no provider delivery test ran.

## Risks and unknowns

- A single broker and single host cannot provide broker or host high availability.
- Redis queue persistence, Redis eviction policy, and behavior after container
  recreation are not configured or verified in the Compose definition. Loss of
  queued work must be characterized.
- No worker termination/restart, redelivery, poison-message, duplicate delivery,
  rebalance, queue backlog, or broker disk exhaustion scenario was run.
- Exact topic creation, replication, retention, message compatibility, consumer
  groups, and broker disk usage at deployment are not established.
- Several Compose services publish broker and Redis ports beyond the host; see
  [Docker/VPS compatibility](docker-vps-compatibility.md).

## Required acceptance tests

In isolated infrastructure, test worker termination at each transaction boundary,
Redis disconnect/reconnect, Kafka/Redpanda disconnect and rebalance, duplicate
message delivery, malformed poison payload, dead-letter replay, queue backpressure,
outbox lease expiry, and bounded retries. For each scenario assert one durable
financial outcome, explain any at-least-once behavior, and prove no silent loss.
If reducing service count is considered, treat it as a separate architecture change
with data migration and compatibility testing; do not remove a broker or worker
based on cost alone.

