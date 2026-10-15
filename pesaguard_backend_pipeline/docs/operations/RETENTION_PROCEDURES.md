# Retention Cleanup Procedures

## Approval gate

`retention_policy.json` must set `approval_required` to the boolean `true`.
Every destructive cleanup run requires an externally verified, one-time
approval reference supplied with:

- `--approval-reference`: identifier for a one-time approval returned by the
  external retention-approval API

Configure the API base URL and bearer token through the deployment secret
manager using `PESAGUARD_RETENTION_APPROVAL_API_URL` and
`PESAGUARD_RETENTION_APPROVAL_API_TOKEN`. The URL must use HTTPS and must not
contain credentials, query parameters, or a fragment. Never put the token in a
command line, source file, or logged configuration. Restrict outbound network
access to the configured authority host; this URL is trusted deployment
configuration and must never come from a request or tenant-controlled value.

Before opening the database, cleanup requests
`GET /v1/retention-approvals/{reference}` over the configured HTTPS API. The
endpoint must return HTTP 200 and JSON with `status: "approved"`, a non-empty `approver_id`, an
exact `tenant_id` (or `"all"` for an unscoped run), the matching lowercase
64-character `policy_sha256`, and a future timezone-aware `expires_at`.
The hash is over the canonical effective retention policy, including its
version, all configured rules, the active cleanup windows, and batch size.
Policy overrides therefore require a matching approval for the new hash.

Example verification response:

```json
{
  "status": "approved",
  "approver_id": "operator-42",
  "tenant_id": "tenant-123",
  "policy_sha256": "<64-character-lowercase-sha256>",
  "expires_at": "2026-10-15T12:00:00Z"
}
```

After a durable local `approval_verified` audit event is recorded and before
any deletion, cleanup calls
`POST /v1/retention-approvals/{reference}/consume`. It sends `run_id`,
`tenant_id`, and `policy_sha256`, with `Idempotency-Key: {run_id}`. The API
must atomically consume the unexpired approval only when scope and policy hash
match, and return HTTP 200 with `{"status":"consumed","run_id":"<same-run-id>"}`.
Repeated consume requests with that idempotency key must return the same
successful result; one approval must not authorize a different run. The
client uses a five-second timeout, TLS verification, no redirects, a bounded
response size, and at most one retry. Any API, scope, policy, expiry, or
consumption failure blocks deletion.

Use a least-privilege API credential limited to retention-approval reads and
consumption. The client requests `Cache-Control: no-store`; the authority and
any intermediary must not cache approval responses.

The cleanup job records the approver identity obtained from the verified API
response, approval reference, policy hash, and run ID in append-only
`action_audit_entries` start/completion/failure events. Tenant-scoped runs
write audit entries to that tenant. An unscoped run uses the reserved
`__system__` audit tenant and records the effective scope as `all`.

## Run procedure

1. Preview the target scope and eligible rows without approval metadata:

   ```powershell
   python pesaguard_backend_pipeline\retention_cleanup.py --dry-run --tenant-id TENANT_ID
   ```

   The summary includes `retention_policy_sha256`. Submit that hash and the
   exact tenant scope to the external authority when requesting approval.
2. Obtain a one-time approval through the configured authority. It must bind
   the exact tenant scope and effective policy hash and must remain unexpired
   at consumption time. Retention periods must be approved by their named
   policy owners; this procedure does not define or change those periods.
3. Invoke the destructive run with the approval reference:

   ```powershell
   python pesaguard_backend_pipeline\retention_cleanup.py --tenant-id TENANT_ID --approval-reference APPROVAL_ID
   ```

   Omitting the reference fails before opening a database connection. A dry
   run never deletes data and does not call the approval API.
4. Verify the returned counts and the corresponding immutable audit events.
   Alert and investigate if the run exits unsuccessfully or the audit event is
   missing.

Omit `--tenant-id` only when the external approval explicitly authorizes a
system-wide cleanup and the job is running with the privileged database access
required for that operation. A cleanup is committed in bounded batches, not as
one all-or-nothing transaction; a failed run can therefore have deleted a
subset of eligible rows. Review its audit event and rerun only after assessing
the partial result. Legal holds and archive-before-purge checks remain in
force.

## Production verification boundary

Before production use, verify the external API's operator authentication,
approval issuance/review controls, atomic one-time consumption semantics,
tenant/policy binding, and availability/alerting. Also verify audit access
controls, notification on partial failure, and retention-policy owner
sign-offs in the deployed scheduler and database environment.
