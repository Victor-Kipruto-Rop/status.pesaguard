# Performance

> Design and operating reference for **Operations / Performance**.

## Purpose

This document defines the role of **Performance** within PesaGuard. It provides a durable reference for production ownership, release safety, monitoring, incident response, and recovery, with enough detail for implementation, review, operations, and future change.

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

## Authentication and device capacity checks

Use disposable PostgreSQL databases and dedicated test identities only. Never point either runner at production. The device migration benchmark creates a uniquely named schema, seeds synthetic session history, runs the actual `20261001_add_user_devices` Alembic revision, checks aggregate correctness and the owner/activity index query plan, and drops the schema in a `finally` cleanup unless `--keep-schema` is explicitly requested.

Example PowerShell setup for a disposable database:

```powershell
$env:PESAGUARD_POSTGRES_TEST_URL = "postgresql://<test-user>:<test-password>@<test-host>/<disposable-db>"
python pesaguard_backend_pipeline/operations/run_device_migration_benchmark.py `
	--session-rows 1000000 --users 20000 --devices-per-user 10 `
	--max-migration-seconds <approved-limit> `
	--min-migration-rows-per-second <approved-throughput> `
	--json-path device-migration-result.json
```

The script needs permission to create and drop schemas. Set thresholds from the release SLO before treating `passed` as an acceptance result; without configured thresholds it verifies data/index correctness only. This isolated benchmark measures migration execution, not production lock impact or replica lag. Validate those separately against a production-shaped staging clone and record lock waits, WAL volume, replica delay, and rollback time.

The endpoint runner needs dedicated accounts in a disposable test tenant. Supply its user pool through a secret-managed `PESAGUARD_AUTH_LOAD_USERS_JSON` environment variable; never check credentials into a file. Local targets may use HTTP. Non-local targets require HTTPS and the explicit `--allow-nonlocal-target` opt-in, and every run requires `--confirm-test-tenant`.

```powershell
$env:PESAGUARD_AUTH_LOAD_USERS_JSON = '[{"username":"load-user-1","password":"<secret>","tenant_id":"loadtest-tenant","device_id":"load-device-1"}]'
python pesaguard_backend_pipeline/operations/run_auth_load_test.py `
	--base-url https://<staging-host> --requests 1000000 --concurrency 100 `
	--confirm-test-tenant --allow-nonlocal-target `
	--max-login-p95-ms <approved-limit> `
	--min-login-requests-per-second <approved-throughput> `
	--max-throttle-rate <approved-rate> `
	--json-path auth-load-result.json
```

The HTTP runner keeps only bounded latency histograms and worker results, reports login and device-list outcomes separately, and accepts `429` as an intentional load-shedding response. Its latency percentiles are histogram upper bounds, not exact samples. Set measurable p95/throughput/throttle targets before release; a run with no performance thresholds is diagnostic only. Use enough test accounts and devices to avoid measuring a single-account policy limit as application capacity. Monitor database pool saturation, Redis, session table growth, and risk alerts throughout the run, and clean up the dedicated test data afterward.

Failure-mode checks:

```powershell
pytest pesaguard_backend_pipeline/tests/test_security_event_instrumentation.py -q
pytest pesaguard_backend_pipeline/tests/test_device_risk_auth.py pesaguard_backend_pipeline/tests/test_login_attempt_protection.py -q
$env:PESAGUARD_RUN_POSTGRES_INTEGRATION = "1"
$env:PESAGUARD_POSTGRES_TEST_URL = "postgresql://<test-user>:<test-password>@<test-host>/<disposable-db>"
pytest pesaguard_backend_pipeline/tests/test_postgres_concurrency.py -q
$env:PESAGUARD_RUN_REDIS_INTEGRATION = "1"
pytest pesaguard_backend_pipeline/tests/test_security_event_instrumentation.py -q -k redis_rate_limit_state
```

The Redis outage test injects a failure and verifies that configured auth throttling fails closed with `503`, while generic rate-limit callers preserve the documented local fallback. The opt-in Redis test verifies that independent limiter instances share one Redis bucket. These do not replace a staging test that disconnects Redis across multiple application workers. The PostgreSQL race test verifies concurrent first-device session creation yields one device row and increments its session count atomically.

## Open work

- Replace this design baseline with implementation-specific diagrams, schemas, examples, and measured SLOs as the capability matures.
- Link the final implementation, migration revision, tests, dashboards, and runbook from this document.
- Review this document whenever the public contract, ownership boundary, or recovery behavior changes.
