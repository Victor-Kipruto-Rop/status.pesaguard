# Authentication

> Design and operating reference for **Security / Authentication**.

## Purpose

This document defines the role of **Authentication** within PesaGuard. It provides a durable reference for identity, authorization, confidentiality, integrity, threat reduction, and evidence, with enough detail for implementation, review, operations, and future change.

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

Developer Platform API keys (`pgk_`) are synchronized through
`POST /internal/v1/developer-api-keys/sync`. When the configured service-auth
mode requires HMAC, Core verifies a timestamped HMAC-SHA256 over the method,
exact raw request target, and exact request body. Configure
`PESAGUARD_PIPELINE_KEY_SYNC_SECRET` to the same independent random value used
by the Developer Platform. The pipeline stores only the supplied SHA-256
verification digest and opaque key identity; the plaintext API key is never
sent. Each record is assigned a tenant partition derived from its organization,
project, and environment, and ORM queries for that key are filtered to that
partition. Public requests can use `Authorization: Bearer pgk_...` or
`X-API-Key: pgk_...`; conflicting credentials, stale signatures, scope
mismatches, and requests outside a key's tenant or IP allowlist are denied.

The tenant partition is canonical across Core and Developer Platform:
normalize each identifier as a lowercase, hyphenated UUID, then hash the exact
UTF-8 bytes of
`developer-platform:v1|<organization UUID>|<project UUID>|<environment UUID>`.
The tenant ID is `dp_` plus the lowercase SHA-256 hex digest. Core exposes
`GET /internal/v1/tenants/resolve` for authenticated service-to-service lookups; it
requires exactly one `organization_id`, `project_id`, and `environment_id`
query parameter and returns only `{"tenant_id":"dp_..."}`. The resolve request
uses the same timestamp/signature headers and 300-second skew window as key
sync, with the exact raw request target (including its query string) in the
canonical HMAC input:
`timestamp + "\nGET\n/internal/v1/tenants/resolve?<raw query>\n"` when HMAC
authentication is enabled. Both internal routes require the configured service
authentication mode: HMAC in `HMAC_ONLY`, JWT plus HMAC in `DUAL_REQUIRED`, or
JWT in `JWT_PRIMARY` (subject only to the explicit fallback policy below).

Synced and locally issued API keys are authorized only by their explicit,
normalized scopes validated against Core's existing customer permission
registry and by trusted tenant/request context. Unknown, internal, service, and
wildcard scopes are rejected before persistence. `ApiKeyRecord.role` remains
for compatibility/display and never grants authorization; key issuance
requires explicit scopes rather than deriving them from a role.

### Developer Platform service authentication (Core implementation)

Core supports Java's RS256 service-JWT contract on internal key-sync,
tenant-resolve, and API-key lifecycle routes. The default
`PESAGUARD_PIPELINE_SERVICE_JWT_MODE=HMAC_ONLY` preserves the current HMAC
checks. `DUAL_REQUIRED` requires both a valid JWT and the existing HMAC;
`JWT_PRIMARY` requires a valid JWT. JWTs must have `kid`, `iss=developer-platform`,
`aud=core-api`, `sub=svc-developer-platform`, integer `iat`/`exp` with a
60–300-second lifetime, a UUID `jti`, and a JSON-array `scope` claim containing
exactly one supported service scope. The allowed internal scopes are
`service:sync`, `service:tenant:read`, `service:key:revoke`, and
`service:key:suspend`; each route requires its matching scope and rejects a
token containing that scope plus any other otherwise-allowed service scope.
These scopes are internal service authorization only and are not accepted as
customer API-key scopes. Core fetches public keys from
`PESAGUARD_PIPELINE_SERVICE_JWT_JWKS_URL`, requires HTTPS, caches valid JWKS
for 30 seconds, and refreshes on an unknown `kid` so an active/overlap key
rotation is recognized. Private JWK material is rejected.

JWT-authenticated mutating sync, revoke, and suspend requests consume their
`jti` atomically in Redis with `SET NX` and an expiry no longer than token
lifetime plus 30 seconds. Redis unavailability fails closed with 503 in
JWT-required modes. Configure
`REDIS_URL` to the shared Redis service; a process-local replay cache is not
used.

`POST /internal/v1/keys/{key_id}/revoke` and
`POST /internal/v1/keys/{key_id}/suspend` require `Idempotency-Key` and a JSON
body containing exactly `organization_id`, `project_id`, `environment_id`,
`tenant_id`, and integer `source_version`. IDs are UUIDs; Core derives the
tenant ID from the organization/project/environment tuple and checks it against
the key's persisted binding. `source_version` must be greater than the key's
current source version. Idempotency is scoped to the service subject, operation,
key ID, and header value; a retry with the same canonical body returns its
original safe response, while reuse with a different body returns 409. Records
expire after 24 hours and expired rows are pruned during lifecycle requests.
Success returns HTTP 200 with
`{"status":"revoked","key_id":"...","source_version":N,"active":false}` for
revoke or `{"status":"suspended","key_id":"...","source_version":N,"active":false}`
for suspend. Invalid payloads return 400; tenant/version or idempotency
conflicts return 409; unknown key IDs return 404.
Revocation is terminal (`revoked_at` is set); suspension is nonterminal and
can be resumed by a later synchronization with a newer source version. Both
operations update authorization state and append a secret-free audit event in
the same database transaction. Existing authentication checks `active` and
`revoked_at`, so a committed suspend or revoke immediately denies that key.
When HMAC is required, lifecycle requests use the existing
`X-PesaGuard-Timestamp` and `X-PesaGuard-Signature` headers over the timestamp,
`POST`, exact raw request target, and exact raw body.

The Developer Platform's `PipelineApiKeySynchronizer` calls the Core sync,
revoke, and suspend endpoints with operation-specific service JWTs, canonical
tenant context, source versions, and idempotency keys. Lifecycle requests must
not reuse a sync token. The private OpenAPI contract is maintained in
[`internal-api-v1.openapi.yaml`](./internal-api-v1.openapi.yaml); it must remain
separate from the customer-facing Developer Platform API specification.

Internal sync, resolve, and lifecycle operations also use endpoint-specific
token-bucket limits keyed by the authenticated service subject and operation,
in addition to the existing client-IP limiter. Defaults are 600 sync requests
per minute and 120 per minute for resolve and each lifecycle operation; the
limits can be set with `PESAGUARD_INTERNAL_KEY_SYNC_LIMIT_PER_MINUTE`,
`PESAGUARD_INTERNAL_TENANT_RESOLVE_LIMIT_PER_MINUTE`, and
`PESAGUARD_INTERNAL_KEY_LIFECYCLE_LIMIT_PER_MINUTE`. Production requires the
distributed Redis limiter; an unavailable or unconfigured limiter fails closed.
The Core API reuses or creates request/correlation identifiers and propagates
`X-Request-ID`, `X-Correlation-ID`, and W3C `traceparent` context in responses.

Emergency HMAC-only fallback is disabled by default. It is permitted only in
`JWT_PRIMARY` when
`PESAGUARD_PIPELINE_SERVICE_JWT_ALLOW_HMAC_FALLBACK=1` and the request has no
`Authorization` header; an invalid or malformed presented JWT is always
rejected, with no downgrade. `DUAL_REQUIRED` never falls back. Set the flag to
`0` (or leave it unset) outside a specifically authorized emergency. This
fallback uses the existing timestamp/HMAC verifier and shared secret; it does
not bypass authentication or apply in `DUAL_REQUIRED`.

**Rollout status:** Core defaults to `HMAC_ONLY`; no production environment or
deployment configuration was changed. Java's JWT support is opt-in and does
not change the production authentication mode until it is explicitly configured.
Validate JWKS reachability, public-key overlap, clock synchronization, shared
Redis availability, and every route-specific service scope in staging before
an operator changes the mode. Keep Java private signing keys exclusively in
Java secret configuration; Core receives only the HTTPS JWKS URL.

## Operations

Monitor request success and latency, reconciliation health, database pool health, Kafka and outbox lag, retry and dead-letter rates, provider errors, audit delivery, and backup freshness. Every alert should identify an owner, a runbook, a severity, and a recovery target.

## Validation

At minimum, validate syntax and imports, focused unit behavior, cross-tenant isolation, migration compatibility, API contract behavior, and failure/retry paths. For integration changes, run the PostgreSQL, Redis, and Kafka checks when those services are available and record unavailable dependencies explicitly.

## Open work

- Replace this design baseline with implementation-specific diagrams, schemas, examples, and measured SLOs as the capability matures.
- Link the final implementation, migration revision, tests, dashboards, and runbook from this document.
- Review this document whenever the public contract, ownership boundary, or recovery behavior changes.
