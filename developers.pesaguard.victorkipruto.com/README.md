# PesaGuard developer-platform backend

This repository contains the standalone Java 21/Spring Boot backend for the PesaGuard developers portal.

## Implemented vertical slice

- registration, login, logout, and current-session flows;
- explicit organization selection at login for multi-organization accounts;
- revocable opaque bearer sessions stored as SHA-256 hashes, with organization session-TTL and maximum-session limits;
- persistent account/IP login throttling;
- organization lifecycle (create, update, verify, suspend, restore, disable, soft delete, ownership transfer);
- organization membership management, invitations, and immutable membership history;
- per-organization security settings (auth methods, session TTL/idle/max sessions, credential policy, IP allowlist, security event types);
- permission-based authorization (RBAC): a 22-permission catalog, six built-in roles, organization-scoped custom roles with no-privilege-escalation and separation-of-duties enforcement;
- project lifecycle with metadata, ownership transfer, project members, and project settings;
- tiered environments (DEVELOPMENT/SANDBOX/STAGING/PRODUCTION) with configuration, limits, append-only history, and forward-only promotion;
- backend-enforced Go-Live readiness, verification history, idempotent launch records, and production-key launch gating;
- durable Go-Live revocation; Production API keys are rejected as soon as the persisted launch is revoked;
- tenant-owned support tickets, support-operator inbox/resolution endpoints, and email notifications for new and resolved tickets;
- tenant-scoped projects and sandbox-only environments;
- tenant-guarded API-key issue/list/revoke flows;
- API-key lifecycle (CREATED/ACTIVE/SUSPENDED/REVOKED/EXPIRED) with rotation lineage, per-key IP allowlists, usage metadata, and append-only key history;
- HMAC-protected API-key storage and one-time secret disclosure;
- API-key-authenticated, sandbox-scoped transactions endpoint for validating a newly issued key; transaction results are persisted records only and empty until transaction processing is implemented;
- organization-scoped invoice requests, operator-issued invoices, idempotent payment attempts, provider status checks, Stripe-signed webhooks, and audited manual reconciliation;
- per-user developer request and appearance preferences with optimistic concurrency, organization-scoped service accounts, and an operator-managed published changelog;
- append-only, organization sequence-locked, HMAC hash-chained audit events;
- liveness/readiness endpoints, Actuator, correlation IDs, and safe error envelopes.

Changelog entries are not generated from commits or seeded as sample releases.
Only verified shipped changes should be authored and published through the
operator workflow described in [Changelog publishing](docs/changelog-publishing.md);
until then, the developer portal correctly shows an empty feed.

Payment execution for the developer-facing sandbox transaction API, production transaction processing, and webhook ingestion for API transaction events are not implemented. The separate platform invoice checkout and manual settlement workflows do not invent plans, prices, invoices, or balances. The sandbox transactions endpoint does not generate sample transactions or simulate payments.

## Local prerequisites

- JDK 21;
- PostgreSQL 17 (or Docker Compose);
- Gradle wrapper `backend/gradlew` with Gradle 9.8.0.

Copy `.env.example` to the repository-root `.env`, then provide independent
32-byte Base64 HMAC keys and an RSA JWT key pair. Never commit runtime secrets.
When `bootRun` is launched from `backend`, Spring imports this optional root
`.env` as properties; explicit process environment variables take precedence.
Docker Compose reads the same root file and supplies container-specific
database settings. For local Mailpit outside Compose, use
`MAIL_HOST=127.0.0.1` and disable required STARTTLS; this avoids an IPv6
`localhost` lookup when the SMTP port is published only on IPv4 loopback.
Email delivery defaults to Resend; set
`RESEND_API_KEY` and the verified `PESAGUARD_MAIL_FROM` in the ignored root
`.env`. SMTP/Mailpit is available only when `PESAGUARD_EMAIL_PROVIDER=smtp` is
explicitly selected.
`SUPPORT_TICKET_EMAIL` defaults to `support@pesaguard.co.ke`; override it with
the monitored support-team inbox for each deployment. New tickets notify that
inbox, while resolution updates are
sent to the ticket creator through the notification delivery system. The
internal operator inbox is exposed at `/internal/support/tickets` and requires
`SUPPORT_READ`; resolving a ticket requires `SUPPORT_RESOLVE` and an operator
token with an audit reason.

```powershell
$env:DATABASE_URL = 'jdbc:postgresql://localhost:5432/pesaguard_developer_platform'
$env:DATABASE_USERNAME = 'postgres'
$env:DATABASE_PASSWORD = '<local database password>'
$env:PESAGUARD_CREDENTIAL_HMAC_KEY = '<openssl rand -base64 32>'
$env:PESAGUARD_AUDIT_HMAC_KEY = '<different openssl rand -base64 32>'
$env:PESAGUARD_OPERATOR_HMAC_KEY = '<independent operator key>'
$env:PESAGUARD_JWT_PRIVATE_KEY = '<Base64 PKCS#8 RSA private key>'
$env:PESAGUARD_JWT_PUBLIC_KEY = '<Base64 X.509 RSA public key>'
$env:PESAGUARD_ALLOWED_ORIGINS = 'http://localhost:5173'
$env:PESAGUARD_WEBAUTHN_RP_ID = 'localhost'
$env:PESAGUARD_WEBAUTHN_ORIGINS = 'http://localhost:5173'
```

Run from `backend`:

```powershell
.\gradlew.bat :compileJava
.\gradlew.bat test
.\gradlew.bat bootJar
```

The Testcontainers integration suite uses PostgreSQL and is marked to skip when Docker is unavailable. Unit tests do not require Docker.

## Runtime configuration

The authoritative configuration names are in `backend/src/main/resources/application.yml`. The service uses `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`; the Compose file maps its `POSTGRES_*` variables to those application variables. Registration is disabled by default. Set `PESAGUARD_REGISTRATION_ENABLED=true` only in an approved local/test environment.

Flyway owns the schema. Hibernate runs in `validate` mode. Do not use `ddl-auto=update` for a shared or financial database.

### Billing providers

Provider credentials are read only from environment variables; the developer portal never collects or returns them. Providers appear as available only when their required settings are present:

- Stripe: `PESAGUARD_BILLING_STRIPE_SECRET_KEY`, `PESAGUARD_BILLING_STRIPE_WEBHOOK_SECRET`, `PESAGUARD_BILLING_STRIPE_SUCCESS_URL`, and `PESAGUARD_BILLING_STRIPE_CANCEL_URL` (optional `PESAGUARD_BILLING_STRIPE_BASE_URL`).
- PayHero: `PESAGUARD_BILLING_PAYHERO_API_TOKEN`, `PESAGUARD_BILLING_PAYHERO_CHANNEL_ID`, and `PESAGUARD_BILLING_PAYHERO_CALLBACK_URL` (optional `PESAGUARD_BILLING_PAYHERO_BASE_URL`).
- M-Pesa Daraja: `PESAGUARD_BILLING_DARAJA_CONSUMER_KEY`, `PESAGUARD_BILLING_DARAJA_CONSUMER_SECRET`, `PESAGUARD_BILLING_DARAJA_SHORT_CODE`, `PESAGUARD_BILLING_DARAJA_PASSKEY`, and `PESAGUARD_BILLING_DARAJA_CALLBACK_URL` (optional `PESAGUARD_BILLING_DARAJA_BASE_URL`).
- Airtel Money: `PESAGUARD_BILLING_AIRTEL_CLIENT_ID`, `PESAGUARD_BILLING_AIRTEL_CLIENT_SECRET`, `PESAGUARD_BILLING_AIRTEL_COUNTRY`, `PESAGUARD_BILLING_AIRTEL_CURRENCY`, and `PESAGUARD_BILLING_AIRTEL_CALLBACK_URL` (optional `PESAGUARD_BILLING_AIRTEL_BASE_URL`).
- Manual settlement: `PESAGUARD_BILLING_MANUAL_INSTRUCTIONS`.

Configure provider callback URLs to the matching `/api/v1/billing/webhooks/{provider}` route. Provider account credentials and official production callback/request contracts must be verified before enabling a live integration. In particular, Daraja and Airtel Money are implemented against their currently configured adapter contracts but were not live-tested here. No real provider credentials or accounts were available in this workspace.

## API contract

The checked-in contract is `backend/src/main/resources/static/openapi/pesaguard-developer-v1.yaml` and is served at `/openapi/pesaguard-developer-v1.yaml`. It documents the endpoints implemented in this slice, including the one-time API-key response and the tenant-scoped resource rules.

Personal developer preferences are available at `/api/v1/developer/preferences`. Appearance uses `PATCH /api/v1/developer/preferences/appearance` with `theme` (`SYSTEM`, `LIGHT`, or `DARK`) and the current `expectedVersion`; stale updates return `409` rather than overwriting a newer preference.

## Documentation

- `docs/architecture.md` — module boundaries, transaction model, and tenant isolation;
- `docs/security.md` — credential handling and security controls;
- `docs/rbac.md` — permissions, built-in and custom roles, production access review;
- `docs/environment-limits.md` — per-environment limits and where each is enforced;
- `docs/environment-access-policies.md` — role-specific environment access and policy API;
- `docs/go-live.md` — Production readiness checks, verification, launch authorization, and launch history;
- `docs/operations.md` — health, deployment, backup, and incident notes.

## Multi-organization login

Login accepts an optional `organizationId`. Without it, the portal automatically
uses the user's last-accessed active workspace; if that workspace is no longer
available or none has been recorded, it falls back to an active membership.

- A supplied `organizationId` selects only among the account's active memberships.
- A supplied `organizationId` that is not an active membership of the account fails with `401 INVALID_CREDENTIALS`, so selection cannot be used to probe membership.
- Every successful password check still requires email MFA before a session is issued.

Suspended, disabled, and deleted organizations are not loginable; their sessions are revoked when the lifecycle status changes.

## Verification status

Evidence from this workspace (2026-10-07):

- Backend `:compileJava` and the full `test` task completed successfully: 1,000
  tests reported, with 0 failures/errors and 26 skipped.
- Frontend `npm run typecheck` and `npm run build` completed successfully. Vite
  reported a large-bundle advisory; the build did not fail.
- All 12 multiline shell blocks in the updated GitHub Actions workflow passed
  Bash syntax validation, and `git diff --check` passed for the changed files.

NOT VERIFIED in this environment:

- The 25 Docker-dependent PostgreSQL Testcontainers cases were skipped because
  the local Docker daemon is unavailable. A separate optional pipeline sync
  smoke test was skipped because no integration target credentials are set.
- The changed Kubernetes manifests could not be checked against a live API
  server; the configured local Kubernetes endpoint was unavailable.
- GitHub protected-environment settings, image publication, staging/production
  rollout, and Prometheus collection were not exercised; this workspace has no
  authenticated GitHub CLI session or reachable cluster.
- Database backup/restore, real email delivery, and live provider callbacks have
  not been verified. They require target-environment access and, for providers,
  sandbox credentials and a safe test recipient/transaction.

Treat the service as an unverified development artifact until the required
database-backed CI tests pass and the target-environment checks are recorded.
