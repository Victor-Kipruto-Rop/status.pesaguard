# Go-Live

Go-Live runs backend readiness checks for one selected Production environment,
stores verification history, and records a launch only when required checks
pass. The Developer Platform's active project/environment selection is used;
Sandbox and other environment types are rejected by the backend.

Production access request/review records are not required for launch. The
legacy request/review API is retained separately; a production API key is
accepted only after a `LIVE` Go-Live launch exists for its exact project and
environment.

## API

All endpoints are scoped to the authenticated organization, project, and
environment:

```text
GET  /api/v1/projects/{projectId}/environments/{environmentId}/go-live/readiness
POST /api/v1/projects/{projectId}/environments/{environmentId}/go-live/verifications
GET  /api/v1/projects/{projectId}/environments/{environmentId}/go-live/verifications
GET  /api/v1/projects/{projectId}/environments/{environmentId}/go-live/verifications/{jobId}
POST /api/v1/projects/{projectId}/environments/{environmentId}/go-live/launches
GET  /api/v1/projects/{projectId}/environments/{environmentId}/go-live/launches
POST /api/v1/projects/{projectId}/environments/{environmentId}/go-live/suspend
POST /api/v1/projects/{projectId}/environments/{environmentId}/go-live/resume
```

Verification and launch calls must include an `Idempotency-Key` header
containing 8–128 characters. A new verification is evaluated synchronously and
returns its persisted result in the same response, avoiding a background-queue
wait for these database-backed checks. Older queued jobs are still completed by
the background worker. A repeated key returns the original job.

While the request is in progress, the portal displays an approximate
30-second completion window and updates the remaining time. This is a UI
estimate, not a service-level guarantee; if the estimate passes, the portal
continues waiting for the backend result.

Launch requires the `production:launch` permission and a `READY` verification
completed within the previous 15 minutes. Current readiness is evaluated again
at launch time, so a stale or newly blocked environment cannot be activated.
The selected environment is locked while a launch is recorded; a repeated key
returns the original launch record. Failed current readiness is stored as
`LAUNCH_FAILED` with a safe reason and a verification reference.

Suspension reuses the Production environment's existing status. Suspended
environments reject API-key traffic even when a prior Go-Live launch exists.
Resumption is rejected until every blocking readiness check passes; both
transitions are audited.

Read operations require `production:view`, environment-read, and project-read.
Starting a verification additionally requires `production:verify`; verification
rechecks that the initiating account and organization are active, the membership
still exists, and the current role still grants verification before evaluation.
Launch requires
`production:launch`, environment-update, project-manage, and the environment
`DEPLOY` capability. Suspend and resume additionally require
`production:suspend` and `production:resume`, respectively. Built-in OWNER and
ADMIN roles include the lifecycle permissions, while DEVELOPER can view and
verify. Custom roles can receive permissions through normal role management.
These checks are server-side.

## Checks currently evaluated

- The initiating account exists, is active, and has a verified email.
- The organization is active and the project is active.
- The selected environment belongs to that organization/project, is a
  Production environment, and is active.
- The server-owned Production API Base URL is present.
- At least one active, non-expired API key is bound to this environment. Secret
  material is never returned or recorded.
- If organization MFA policy applies to the initiating account, a confirmed
  authenticator must be enrolled.
- Active Production webhooks must use HTTPS and have signing configured. No
  active webhook is a warning, not a blocker, for integrations that do not use
  event delivery.

`BLOCKER` and `CRITICAL` failed checks prevent launch. Warnings are surfaced
but do not block. Readiness percentage is descriptive and never overrides a
failed required check. Verification snapshots and launch records are persisted
in `golive_verifications` and `golive_launches`; jobs are persisted in
`golive_verification_jobs`. Launch start/completion/failure and verification
events are written to the audit trail.

The Go-Live Readiness page presents an ordered journey: account and environment,
Production credentials, security policy, optional webhooks, fresh verification,
and launch. Backend check failures include direct remediation destinations when
the platform has an appropriate page; platform/support-owned checks link to
support instead. Every stage shows its current result, and verification and
launch actions are gated by the active role's permissions. The remaining
Production setup, Integration, Credentials, Webhooks, Verification, Launch,
and Post-Launch sections remain directly navigable. Post-Launch displays real,
selected-environment usage
totals, recent request logs, and webhook delivery attempts from the existing
usage and events APIs. Usage and request data require `usage:read`; delivery
history requires `webhook:read`. Each source reports unavailable data
separately when the role lacks access or the API fails. Credential and webhook
management remain in their existing authoritative pages.

## Scope and limitations

This implementation deliberately reuses the existing project, environment,
credential, webhook, security, and audit systems. It does not create duplicate
credential or webhook-management screens. Webhook delivery success/endpoint
verification, live API connectivity probes, legal agreements,
recent-password/MFA reauthentication, deployment-provider
orchestration, Go-Live-specific notifications, external uptime alerts,
first-successful-request-since-launch tracking, and rollback are not yet
automated checks or launch actions. The displayed telemetry is not an uptime
guarantee and must not be represented as a passed readiness check.

Billing is currently disabled in the developer workspace and is not a
Go-Live prerequisite.
