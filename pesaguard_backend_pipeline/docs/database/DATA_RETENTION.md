# PesaGuard Data Retention

## Authoritative policy

[`retention_policy.json`](../../retention_policy.json) is the configured policy
source. Its values and environment overrides are **not evidence of legal,
contractual, or customer approval**. The named policy owners must approve each
effective period before production cleanup is enabled. The discrepancy cleanup
period must be set through an approved `discrepancies` category or the
`PESAGUARD_RETENTION_DAYS_DISCREPANCIES` deployment override. While neither is
configured, cleanup fails closed for discrepancies and reconciliation outbox
records; it does not inherit the transaction period. Obtain owner approval and
bind the exact effective value to each cleanup approval before enabling those
deletions.

Do not copy retention periods into SQL, a second configuration file, or an
operator command. The cleanup process hashes the effective policy and its
active cleanup windows; the external approval must match that exact hash and
tenant scope.

## Cleanup controls

Destructive cleanup is available only through
[`retention_cleanup.py`](../../retention_cleanup.py) with the external
one-time-approval workflow in
[`RETENTION_PROCEDURES.md`](../operations/RETENTION_PROCEDURES.md). Do not
purge data with ad-hoc SQL. Cleanup is tenant-scoped when a tenant is supplied,
deletes in bounded batches, honors active legal holds, and writes append-only
audit events. A failed run can have committed earlier batches; review its run
audit before retrying.

Transaction rows are eligible only when both `archived_at` and a non-empty
`archive_object_key` are present. Those columns alone do not prove that the
archive object is retrievable or intact. The current `ArchiveManager` uses a
local object-store implementation and is not wired as a production archival
worker or as a verifier for the transaction purge path. Until a production
object-store adapter, durable manifest verification, and end-to-end archive
workflow are configured and exercised, keep destructive transaction cleanup
disabled.

Published outbox records, expired processed-transaction records, discrepancies,
and dead letters have separate cleanup code paths. Audit records remain
append-only; cleanup reports eligible audit archival candidates and does not
delete them.

## Tenant offboarding and erasure

Tenant export, legal-hold review, authorization, retention approval, and
deletion must use the approved offboarding workflow. This document does not
define a grace period or authorize direct database deletion. Verify tenant
scope and dependent records, preserve required financial and audit evidence,
and record the request, decision, execution, and verification in the audit
trail.

## Production gates

- Record owner approval for every effective retention period, including
  discrepancies and raw provider evidence.
- Verify the external approval authority's identity, review, scope binding,
  one-time consumption, availability, and alerting controls.
- Connect the archival writer and verifier to the production object store;
  prove integrity and retrieval before enabling archive-before-purge.
- Exercise a tenant-scoped dry run and a reviewed isolated cleanup/restore
  drill. Verify legal holds and audit evidence before staged rollout.
