# ADR-008: External Approval API for Retention Purges

## Status

**Accepted.** Production use remains gated on live verification of the configured approval authority and owner-approved retention periods.

## Context

Retention cleanup permanently deletes financial and operational records in bounded batches. A locally supplied approver name and reference do not prove that an authorized person approved the exact tenant scope or policy. The repository's organization-lifecycle approval workflow is not a suitable authorization boundary for data purges.

## Decision

Before any destructive cleanup, PesaGuard will verify a one-time approval with a configured external HTTPS API:

1. `GET /v1/retention-approvals/{reference}` returns `status`, `approver_id`, `tenant_id`, `policy_sha256`, and timezone-aware `expires_at`.
2. PesaGuard requires an approved, unexpired response whose tenant scope and lowercase SHA-256 match the requested cleanup and effective retention policy.
3. PesaGuard commits a local append-only approval audit event, then calls `POST /v1/retention-approvals/{reference}/consume` with the generated cleanup `run_id`, tenant scope, and policy hash. The idempotency key is the run ID.
4. PesaGuard begins deletion only after the API confirms consumption for that same run ID. Verification, consumption, or audit failures fail closed.

The effective policy hash covers the policy version, all configured retention rules, effective cleanup windows (including environment overrides), and deletion batch size. Dry runs do not contact the approval API and return the hash that an approver must authorize.

The approval API base URL and bearer token are deployment configuration (`PESAGUARD_RETENTION_APPROVAL_API_URL` and `PESAGUARD_RETENTION_APPROVAL_API_TOKEN`). The token is injected from a secret manager. The configured host must be restricted by deployment egress policy.

## Alternatives considered

- **CLI-supplied approver and ticket fields:** rejected because the caller could self-assert the identity and reference.
- **Reuse organization approvals:** rejected because those records authorize organization lifecycle actions, not destructive data retention.
- **Automatic scheduled purge with policy approval only:** rejected because the selected control requires authorization for every purge run.

## Consequences

### Positive

- A purge cannot start on a missing, unapproved, expired, wrong-scope, or wrong-policy reference.
- The external authority records one-time consumption, while local append-only events record verification, run identity, outcome, and deleted counts.
- API retries use the same run ID and idempotency key; no automatic redirect or unbounded response is accepted.

### Tradeoffs and operational requirements

- The authority must implement the documented endpoint and response contract, bind approvals to scope and policy hash, and atomically consume each approval.
- API unavailability blocks cleanup. An ambiguous consume timeout can require operator review in the authority before retrying with a fresh approved run.
- Cleanup remains batch-committed, not globally atomic; a failure can leave a partial purge, which is recorded and requires operator review.
- Production deployment must validate API identity/review controls, TLS and egress restrictions, credential rotation, audit access, alerting, policy-owner sign-offs, and the real HTTP contract.

## Implementation guardrails

- Never log or commit the bearer token. Do not send transaction data to the approval API.
- Require HTTPS, certificate verification, no redirects, a bounded response body, and a bounded timeout.
- Encode the reference as a path segment and validate all response fields before use.
- Preserve tenant scope and legal-hold/archive-before-purge checks.
- Fail closed if approval verification, consumption, local audit, or database access fails.

## Validation

Local tests cover contract parsing, TLS and request controls, scope/hash/expiry validation, one-time consume semantics, outage handling, and cleanup audit behavior with a fake HTTP authority. A live authority integration and production approval-policy sign-off have not been performed.
