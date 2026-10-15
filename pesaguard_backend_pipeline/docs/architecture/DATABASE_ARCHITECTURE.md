# PesaGuard Data Architecture

PesaGuard uses a Python Flask backend with SQLAlchemy and PostgreSQL as the authoritative data model for application tables such as transaction records, discrepancies, dead-letter records, and action audit metadata. The repository also uses Redis for request throttling and queue dispatch, and Kafka for transaction event publication. SQLite databases exist in the repository for local tests and samples.

## Data flow

1. Webhook or API request enters the Flask service.
2. Request is validated and rate limited.
3. Event store or service logic writes or processes records.
4. Transaction events are published to Redis/RQ and, where configured, Kafka.
5. Reconciliation and discrepancy services update operational records and reports.

## Persistence layers

- PostgreSQL / SQLAlchemy: primary structured persistence.
- Redis: queueing, caching, and rate limiting.
- Kafka: streaming boundary for transaction events.
- SQLite: test and demo local storage.

## Financial data model and controls

- `Transaction` stores tenant-scoped, immutable Daraja source fields and the
  current lifecycle state. `TransactionEvent`, `ActionAuditEntry`, and the
  outbox tables preserve status changes, financial actions, and publication
  intent.
- `TransformationRecord` captures raw, validated, normalized, enriched, and
  processed stage evidence. Reconciliation matches record the matching-engine
  version; derived merchant metrics are stored separately from source records.
- These are logical lifecycle layers within the operational schema, not
  separate PostgreSQL databases or a production data lake. Immutable source
  payloads remain the evidence of record; derived/curated data is reproducible
  and must retain lineage to that source.
- The double-entry ledger schema uses minor-unit integers for entries,
  tenant-and-ledger composite foreign keys, optimistic versions, and RLS on
  every ledger table. It is an internal accounting record, not evidence of
  provider funds transfer or settlement execution.
- Tenant isolation uses tenant predicates in application queries and
  PostgreSQL row-level security for tenant-bearing tables. The current RLS
  revision requires a non-superuser, non-`BYPASSRLS` application role.
- `models.py` defines tenant-leading indexes and uniqueness constraints for
  common transaction, lifecycle, reconciliation, and audit lookups. Existing
  local throughput tests verify correctness under batches, but they do not
  provide production query plans, representative data volumes, or a capacity
  baseline.
- Object archive paths support tenant/source/date partitioning. The PostgreSQL
  transaction table is not physically partitioned: no production volume,
  retention-window benchmark, or query-plan evidence currently justifies a
  partitioning migration.
- `retention_policy.json`, `retention_cleanup.py`, and the archive manager
  define configured retention, bounded cleanup, and legal-hold behavior.
  Cleanup fails closed if the policy cannot be loaded or if mandatory
  per-run approval is disabled. Destructive runs require an external API
  approval bound to the tenant scope and effective policy hash, and consume
  that approval for a unique run ID before deletion. The verified approver,
  reference, and start/completion/failure evidence are appended to the
  immutable audit log. The deployed approval authority must enforce its
  identity, review, scope-binding, and one-time-consumption controls.
- Discrepancy retention currently defaults to the transaction window because
  there is no separate discrepancy category in the policy. Owner approval is
  required before this is treated as a production period. The local archive
  manager is not connected to a production object store or a scheduled
  archive-before-purge path; do not enable transaction purge until object
  integrity and retrieval are verified end to end.
- `backup_postgres.py` creates checksummed backup manifests and supports
  encryption/off-site delivery. Restore commands require explicit target
  confirmation; automated restore drills reject the configured application
  database. Live PostgreSQL restore, PITR, off-site durability, and measured
  RPO/RTO remain deployment gates.

## Data protection posture

Financial and customer-sensitive data should not be sent to Sentry automatically. Use safe metadata, request IDs, correlation IDs, and provider/service tags instead. Avoid logging or capturing secrets, credentials, tokens, PINs, bank account identifiers, or API keys.
