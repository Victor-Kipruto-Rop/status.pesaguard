# Release Process

> Design and operating reference for **Operations / Release Process**.

## Purpose

This document defines the role of **Release Process** within PesaGuard. It provides a durable reference for production ownership, release safety, monitoring, incident response, and recovery, with enough detail for implementation, review, operations, and future change.

## Current position

PesaGuard currently runs a Flask and SQLAlchemy backend with PostgreSQL as the production store, Redis and RQ for asynchronous work, Kafka for event transport, and Alembic for schema migration. M-Pesa/Daraja is the active payment scope. Capabilities described here must be marked as implemented, in progress, or planned rather than implied.

## Design principles

- **Tenant isolation:** every query, mutation, export, cache key, event, audit record, and replay operation carries an authorized tenant and provider-account scope.
- **Durability first:** acknowledge work only after the required durable state or idempotency record is written.
- **Explicit contracts:** validate input, normalize provider data, return stable error shapes, and version public interfaces.
- **Safe failure:** retry transient failures with bounded backoff; dead-letter malformed or exhausted work; never silently discard financial events.
- **Evidence by default:** correlation IDs, structured logs, metrics, append-only audit events, and operator actions must explain what happened.
- **Migration discipline:** use Alembic revisions, backfill safely, and keep compatibility paths deliberate.

## Scope

### Included

- The responsibilities and interfaces implied by this document.
- PostgreSQL persistence and tenant-aware data access.
- Authentication, authorization, validation, observability, and recovery controls.
- Focused tests for normal paths, boundary conditions, isolation, retries, and failure recovery.

### Excluded

- Unscoped cross-tenant access or undocumented administrative bypasses.
- Production schema creation through `Base.metadata.create_all()`.
- Storing plaintext credentials, API keys, tokens, or provider secrets.
- Expanding payment rails beyond the approved product scope without a separate decision.

## Reference workflow

1. Authenticate the caller or verify the provider signature.
2. Resolve tenant and provider-account identity from trusted context.
3. Validate and normalize the request or event.
4. Enforce idempotency before performing a side effect.
5. Persist the durable record and audit event in a transaction.
6. Publish or enqueue work through the outbox or worker boundary.
7. Reconcile, notify, measure, and expose a traceable result.

## Interfaces and invariants

- Public API routes belong under `/api/v1` unless a documented compatibility alias exists.
- IDs are not globally trusted: detail lookups must include tenant and account predicates.
- A retry must be safe to run more than once.
- A successful response must not claim durable processing when the required persistence step failed.
- Audit entries are append-only and must not contain secret material.

## Security and privacy

Use least privilege, short-lived credentials, hashed API keys, explicit scopes, expiry, revocation, rotation, and last-used metadata. Redact sensitive fields at logging boundaries. Apply retention and archival policies by tenant, legal hold, and residency requirements.

## Operations

Monitor request success and latency, reconciliation health, database pool health, Kafka and outbox lag, retry and dead-letter rates, provider errors, audit delivery, and backup freshness. Every alert should identify an owner, a runbook, a severity, and a recovery target.

## Validation

At minimum, validate syntax and imports, focused unit behavior, cross-tenant isolation, migration compatibility, API contract behavior, and failure/retry paths. For integration changes, run the PostgreSQL, Redis, and Kafka checks when those services are available and record unavailable dependencies explicitly.

## Open work

- Replace this design baseline with implementation-specific diagrams, schemas, examples, and measured SLOs as the capability matures.
- Link the final implementation, migration revision, tests, dashboards, and runbook from this document.
- Review this document whenever the public contract, ownership boundary, or recovery behavior changes.

## Implemented CI/CD pipeline

`../../../.github/workflows/backend-release.yml` is the release path for `staging` and `main`:

1. Type-check/build the developer-platform frontend, compile Python, apply Alembic migrations to a disposable PostgreSQL service, and run the backend test suite.
2. Build and publish the backend image to GHCR, scan that exact digest with Trivy, and retain an SBOM for it.
3. On `staging`, request the immutable digest through Render's deploy API, wait for that exact deployment ID to become `live`, then require `status: "ok"` from `STAGING_HEALTH_URL`.
4. On `main`, deploy that run's same digest to staging, wait for its deployment ID and verify health before promoting it to production; wait for the production deployment ID and require `status: "ok"` from `PRODUCTION_HEALTH_URL`.

Release jobs that deploy environments share a concurrency group to prevent `staging` and `main` workflows from racing on the same staging service. Pull-request verification runs use per-PR groups and do not serialize unrelated checks.

Configure these secrets in the GitHub environments:

- `staging`: `RENDER_API_KEY`, `RENDER_SERVICE_ID`, `STAGING_HEALTH_URL`
- `production`: `RENDER_API_KEY`, `RENDER_SERVICE_ID`, `PRODUCTION_HEALTH_URL`

For production Developer Platform API-key operations, configure the same independently generated `PESAGUARD_PIPELINE_KEY_SYNC_SECRET` on both services, set the Developer Platform's `PESAGUARD_PIPELINE_KEY_SYNC_URL` to the pipeline's `/internal/v1/developer-api-keys/sync` endpoint, and set `PESAGUARD_PIPELINE_KEY_SYNC_REQUIRED=true`. The pipeline accepts signed key sync and lifecycle operations; successful calls commit version-checked, idempotent key state and audit changes before returning. Do not rely on an unconfigured optional sync path or assume Kafka is the revocation authority.

Payment/transaction functionality is not production-ready merely because an API route, UI, schema, or callback handler exists. Keep any capability without verified provider acceptance, durable idempotency, reconciliation and recovery disabled or label it clearly as unavailable. No production promotion is permitted on the basis of UI/API surface alone.

Configure each Render service as an image-backed service for the exact GHCR image emitted by the workflow, with pull credentials if the package is private. The workflow submits the full `@sha256:` reference through Render's `imageUrl` API field, not a deploy hook that might ignore the image. Configure required reviewers and branch restrictions on both GitHub environments; naming an environment in workflow YAML alone does not enforce approvals. The `staging` environment must permit both `staging` and `main` branches because main is staged and health-checked before production promotion; `production` should permit only `main`. Verify the settings and secrets in GitHub before relying on deployment protection. Rollback follows [ROLLBACK.md](ROLLBACK.md).

**Current repository gate (checked 2026-10-07):** The remote repository had no `staging` or `production` environment, `main` was not branch-protected, and GitHub did not yet register `backend-release.yml`; the local workflow file was untracked. Do not publish it to `main` until branch protection, environment reviewers/branch policies, Render service settings, and required secret names/values have been provisioned and verified. The workflow's `main` push path promotes to production, so an unguarded push is not a safe validation method.
