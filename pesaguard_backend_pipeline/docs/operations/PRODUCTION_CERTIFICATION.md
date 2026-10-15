# Phase 11 — Production Certification

> **CERTIFICATION DECISION: NOT CERTIFIED — DO NOT SHIP.**
>
> This is the Phase 11 exit gate. It is an evidence record, not a status report.
> Nothing below is pre-ticked. Every line is graded from evidence actually
> collected on 2026-09-17 in this repository and run environment. Where a claim
> could not be verified, it is graded explicitly — a blank is treated as an
> unmet requirement.

| Field | Value |
|---|---|
| Certification date | 2026-09-17 |
| Certifying agent | AI coding agent (PesaGuard Agent Rules) |
| Repository commit | `f5873c1df6367a17e6faefc4ced0ab4b238d190c` (`main`) + uncommitted working tree |
| Certification environment | Windows, Python 3.13.13, **SQLite only** |
| Suite size collected | 314 tests |
| Suite result | **40 failed, 266 passed, 7 skipped, 1 error** (126 s) |
| PostgreSQL / Redis / Kafka available | **No** (nothing listening on 5432/6379; no `psql`, no Docker) |

## Evidence update — 2026-10-14

This update supersedes historical findings below only where noted; the
2026-09-17 run remains a record of that run, not current test evidence.

- The data-quality gate is now covered by `test_data_quality_essentials.py`,
  `test_data_quality_layer.py`, `test_data_quality_remediation_migration.py`,
  `test_transaction_ingestion_hardening.py`, and `test_transaction_routes.py`.
  A focused local regression selection, including schema, lineage, lifecycle,
  database-boundary, and response-contract tests, passed **77 tests** with
  **1 Redis-dependent auth-alias test deselected**. This is SQLite/Windows
  evidence, not PostgreSQL or production certification.
- A separate focused webhook/readiness/data-protection/API selection passed
  **40 tests**, with the same Redis-dependent auth-alias test deselected; this
  selection overlaps the 77-test run. `py_compile` passed for the changed Python
  modules. Repository-wide `compileall` remains blocked by syntax errors in
  unrelated dirty files (`customer_ledger.py`, `_customer_ledger_backup.py`,
  and two `tmp/seg` helpers); those files were not modified.
- The first full backend run was local SQLite: **1,222 passed, 35 skipped,
  31 failed** in 934 seconds. Seven stale fixture failures were corrected
  without weakening production checks. A later full run (before the
  route-inventory-only changes) against local
  PostgreSQL, Redis, and Redpanda completed with **1,242 passed, 36 skipped,
  21 failed** in 1,316.97 seconds. The remaining failures are concentrated in
  auth lifecycle/device/session/password tests and two event-bus delivery tests;
  the full suite is not green.
- On 2026-10-14, the combined ingestion, data-quality, API schema, route,
  least-privilege, ETL, reconciliation, scheduled-report, traceability, and
  security-instrumentation selections passed **80 tests, 1 skipped**. The auth
  alias test now recognizes the documented fail-closed 503 when Redis is
  unavailable while still asserting the downstream auth contract when the
  limiter is available; production limiter behavior was not relaxed.
- The route audit now distinguishes the two HTTP services deployed by the
  Compose configuration: **258 dashboard API routes** (including auth mounts
  and aliases) and **24 webhook receiver routes**. Their combined policy
  inventory has **306 classified method/path records** (201 permission-mapped,
  105 explicit no-customer-scope exemptions); 40 additional source
  routes are recorded separately as not mounted in either service, and three
  `tenant_settings` definitions are separately marked inactive because their
  blueprint is not registered. Runtime
  route enumeration is independent of the permission map, and the audit
  scanner no longer fills missing decorator evidence from that map. The
  verifier passed and the focused route/API regression selection passed
  **55 tests** (2 upstream deprecation warnings). Later focused regressions
  cover read-scope denial for organization and saved-filter mutations, the
  separate analytics-read and discrepancy-resolve scopes for dead-letter
  listing versus replay, the discrepancy-preset write boundary, and
  fail-closed Alertmanager bearer authentication. The Compose Alertmanager
  and receiver share a required protected secret file. Compose validation and
  the scoped changed-file whitespace check passed. The whole-worktree
  `git diff --check` still reports pre-existing whitespace in unrelated
  `report.md` and `test_otp.py`. These checks establish route
  registration and classification coverage, not functional behavior for every
  handler or production authorization certification.
- The auth route inventory now distinguishes authenticated sessions from
  user credentials, client credentials, refresh tokens, email verification,
  password reset, MFA challenges, and OIDC callback/token flows. A focused
  source-contract test checks that `authenticated_session` routes have a
  `require_auth` decorator and that credential-flow routes do not claim that
  decorator. The same focused selection exposed and fixed duplicate Flask
  route registration when `app_4_advanced_features` was imported under both
  module names. It also verified rate-limit HTTP headers, exact retry delay,
  and recovery after token refill. The final selection passed **28 tests**
  with **2 upstream deprecation warnings** on local SQLite. This is not an
  all-route behavioral security test or production evidence.
- The new HTTP load runner supports load, stress, spike, and soak profiles,
  bounded request concurrency and duration, schema-driven JSON payload
  templates, optional Daraja-compatible per-request HMAC signing, and explicit
  test-tenant/non-local-target guards. Its loopback HTTP integration tests and
  the existing auth load-tool tests passed **13 tests**. They verify runner
  behavior only; no PesaGuard service, PostgreSQL/Redis/Kafka stack, sandbox,
  or production-shaped workload was available for a benchmark. The result
  threshold values must come from an approved service SLO.
- A follow-up focused coverage run passed **54 tests** across the load runner
  and alert-delivery suites. It measured **93% line coverage** for the runner
  and **95%** for the notifier module. These local code-coverage results do not
  verify service capacity or production integrations.
- Restore operations now require explicit target confirmation. Restore-drill
  entry points reject a target matching the configured PostgreSQL application
  database and report only a credential-free host, port, and database label.
  Retention cleanup also fails closed when its policy cannot be loaded, instead
  of falling back to shorter code defaults. The combined storage, retention,
  transaction-integrity, archival, and recovery selection passed **98 tests**.
  The focused backup/recovery coverage selection passed **57 tests**; line
  coverage was
  **81%** for `backup_postgres.py`, **86%** for `restore_drill.py`, **100%** for
  `restore_safety.py`, and **93%** for `run_restore_drill.py`. These tests use
  local fixtures and mocked PostgreSQL subprocesses. A separate retention
  coverage run passed **8 tests** and measured **83%** for `retention_cleanup.py`.
  Destructive retention runs now verify and consume an external approval
  bound to tenant scope and the effective policy hash, and append verification,
  completion, or failure evidence to the immutable audit log. The local
  approval/policy regression selection passed **69 tests** and measured **84%**
  coverage for `retention_cleanup.py`, **85%** for `retention_policy.py`, and
  **86%** for `operations/retention_approval.py`. It validates the client
  contract using fakes, not a live approval authority. External service
  controls, live contract verification, and retention-owner sign-offs remain
  production gates.
  This change did not run a live restore or certify production recovery.
- The focused readiness selection passed **64 tests** with **2 upstream
  deprecation warnings**. It covered route authorization, rate-limit behavior,
  workflow contracts, Compose configuration parsing, endpoint load profiles,
  Slack alert URL/retry controls, and the HIBP password-screening path.
  `operations/verify_endpoint_audit.py` also passed at **306 classified
  routes** (201 mapped, 105 explicit exemptions). A broader follow-up run
  passed **87 tests** and had one failure: the password-policy lifecycle test
  received HTTP 503 at final login because Redis is unavailable. The fail-closed
  authentication rate limiter was not weakened.
- The repository-wide blocking Bandit command (`bandit -r . -ll -ii`) completed
  with **0 findings**. Two narrow B608 suppressions cover migration statements
  whose dynamic table and role identifiers are quoted by SQLAlchemy. This is
  local Python 3.13 evidence; pip-audit, Safety, Gitleaks, and the GitHub Actions
  security workflow have not run here.
- Slack alert delivery now accepts only HTTPS incoming webhook URLs hosted at
  `hooks.slack.com`, disables redirects, retries network/429/5xx failures with
  bounded backoff, and stops on permanent HTTP errors. Its focused security
  tests passed as part of the 64-test selection.
- CI now sets the PostgreSQL integration-test flag and supplies a non-bypass
  application-role URL, so the existing PostgreSQL concurrency and tenant-RLS
  tests run in the CI database job. This workflow change has not yet been
  exercised by a GitHub Actions run.
- The complete Alembic chain was applied successfully to the isolated local
  PostgreSQL 16 test database through `20261014_tenant_row_level_security`.
  The database has **75/75 ORM tables** and **150 tenant RLS policies**.
  Focused transaction route tests passed **12 tests** against PostgreSQL and
  Redis; the combined ingestion/data-quality/reconciliation/reporting/security
  selection passed **80 tests, 1 skipped** against PostgreSQL, Redis, and
  Redpanda. These are local dependency-container results, not production
  deployment evidence. Redpanda's direct cluster-health command reports
  healthy, but its Docker healthcheck remains `unhealthy`; container readiness
  should not be treated as verified until that check is corrected.
- The authenticated transaction write route now invokes the same quality gate
  as M-Pesa ingestion only for M-Pesa/Safaricom provider identifiers, returns a
  distinct durable-quarantine response, and preserves the prior non-M-Pesa
  processing path. `dq_report` includes the freshness/null/empty profile.
- The security checklist is no longer merge-conflicted, and the referenced
  `SECRETS_MANAGEMENT.md` exists. The obsolete certification findings B1, B9
  ("no DQ tests"/lineage signature mismatch), and B10 (metrics import-time
  payload marker) must not be treated as current findings; re-run the full
  checks before closing their release gates.
- Removed the unused `PESAGUARD_ALLOW_UNRESTRICTED_WEBHOOK_SOURCE` variable
  from backend workflow test environments. The application has no such
  fail-open override; tests must configure an actual shared secret or IP
  allowlist.

**Current decision remains NOT CERTIFIED.** The latest recorded full backend
suite was not green (**1,242 passed, 21 failed, 36 skipped**) and predates the
authorization, module-import, and rate-limit fixes above. No complete suite
result is available after those changes. The focused local integration results
do not satisfy production certification. The local Redis service and Docker
Engine are unavailable, so distributed rate-limit, PostgreSQL concurrency, and
container-backed checks were not run. Production backup/restore and recovery
drills, representative target load testing, the remaining dependency/history
scans, staging/production smoke tests, and named operator sign-off remain
outstanding. No production system was accessed or changed.

## Grading vocabulary

| Status | Meaning |
|---|---|
| `CERTIFIED` | Implemented **and** passing automated evidence exists, with no known material gap. |
| `PARTIALLY CERTIFIED` | Implemented with some passing evidence, but a material gap or a failing test remains. |
| `NOT VERIFIED` | Capability absent or unexercised; no evidence exists. |
| `BLOCKED` | Cannot be verified in this environment (needs PostgreSQL/Redis/Kafka/Docker/live drill). |

## Historical verification record — 2026-09-17

- `python -m compileall -q pesaguard_backend_pipeline` → exit 0 at the time
  (the 2026-10-14 repository-wide compile attempt is blocked by unrelated dirty
  files, as recorded above)
- `python -m pytest -q --tb=no` → 40 failed / 266 passed / 7 skipped / 1 error
- Cross-domain regression run over every module touched by the remediations
  (`test_features`, `test_disaster_recovery`, `test_phase4_event_bus`,
  `test_phase1_full_integrity`, `test_lifecycle_integrity`, `test_phase3_reconciliation`,
  `test_api_idempotency_contract`, `test_ground_truth_certification`,
  `test_security_event_instrumentation`, `test_export_tenant_scoping`)
  → **52 passed, 0 failed**
- Six focused per-domain pytest runs (Security 15, Data 32, Reliability+Resilience 29,
  Financial 19, Observability 27, Recovery 7f/15p)
- Static inspection of every control surface named below
- Repo-wide scans for unresolved merge-conflict markers, secrets-manager integration,
  soak/spike/stress harnesses, and dead documentation references

**Environment limitation (material):** CI runs the suite against PostgreSQL 15 and
Redis 7. This environment had neither, so every gate that requires those services is
graded `BLOCKED` rather than `CERTIFIED`. A local SQLite pass does **not** imply a
PostgreSQL pass.

## Remediations already applied and verified in the working tree

These were found broken and are now fixed or worked around. They are recorded as
engineering remediation, **not** as certification evidence.

| Remediation | Evidence |
|---|---|
| `pesaguard_backend_pipeline/metrics.py` — repaired a corrupt expression at line 632 (`Discrepancy.resolved == not Discrepancy.resolved:`) back to the `HEAD` form `Discrepancy.resolved == False`. The stray `:` was a hard `SyntaxError`, and because `tests/conftest.py` imports `app_4_advanced_features` → `app`, **the entire suite failed to collect or run** | `compileall` exit 0; suite went from *no collection* to `40 failed / 266 passed / 7 skipped / 1 error` |
| `pesaguard_backend_pipeline/tests/test_features.py` — repaired seeds and fixtures: `status='assigned'` violated `ck_discrepancy_status` (assignment is carried by the `assignee` column, not a persisted status), and every endpoint now carries a real Bearer token because `PESAGUARD_API_AUTH_REQUIRED` defaults on | Was **8 errors**; now **8 passed** |
| `pesaguard_backend_pipeline/event_store.py` — removed an import of a non-existent symbol (`_worker_on_consumer_delivery`) that made **every** DB operation return `ERROR` | Smoke test: `mark_processed` → `STORED` then `DUPLICATE` (idempotency enforced) |
| `pesaguard_backend_pipeline/operations/validate_restore.py` — restored a mangled docstring (`SyntaxError`) and implemented the 12-query integrity contract | `tests/test_disaster_recovery.py` 3 passed |
| `pesaguard_backend_pipeline/data_quality.py` — rebuilt from a two-way file splice (`SyntaxError`) into a single coherent implementation | Compiles; `provenance.py` now imports; `run_data_quality` returns `pass`/`fail` correctly on smoke input |
| `conftest.py` (repo root) — excludes two operator CLIs that match pytest's `*_test.py` pattern from collection | `314 tests collected`, no collection error |
| `.github/workflows/ci.yml` — `JWT_SECRET_KEY` now 39 bytes | App startup no longer rejects the CI secret |

> Provenance note: the root `conftest.py` and the `ci.yml` secret change were found
> already present in the working tree when re-inspected; their authorship should be
> confirmed before commit.

---

## 1. Security

| # | Requirement | Status | Evidence | Gap |
|---|---|---|---|---|
| 1.1 | Authentication | `PARTIALLY CERTIFIED` | `auth_rbac.py` (JWT + password hashing + MFA challenges + token revocation), `tests/test_rbac_enforcement.py`, `tests/test_daraja_auth.py`, `tests/test_otp.py`, `tests/test_readiness_hardening.py::test_production_auth_cannot_be_disabled` — all pass | No external security review; `docs/security/SECURITY_CHECKLIST.md` explicitly asks to be updated "to reflect what's actually implemented" |
| 1.2 | Authorization | `PARTIALLY CERTIFIED` | `rbac.py` + `dashboard/api/models/roles.py` (`ALL_PERMISSIONS` immutable `frozenset`, `enforce_permission` carries tenant context), `dashboard/api/models/test_roles.py` | Role matrix is not reconciled against a documented authority model |
| 1.3 | Tenant isolation | `PARTIALLY CERTIFIED` | `tests/test_export_tenant_scoping.py` passes; alembic `20260913_add_tenant_scope_constraints`; load gate `tenant_isolation: cross_tenant_rows == 0` in a committed PASS artifact | `tests/test_tenant_scope_constraints.py::test_database_rejects_empty_tenant_ids` **errors** (`CREATE INDEX ix_communication_notification_tenant_status_created` fails) — DB-level rejection of empty tenant IDs is unproven |
| 1.4 | Encryption | `PARTIALLY CERTIFIED` | Fernet field-level encryption at rest (`data_protection.py` `enc:v1:` envelope, hard-fails in production without `PESAGUARD_PAYLOAD_ENCRYPTION_KEY`), provider-secret encryption migration `20260908_encrypt_provider_configuration`, Ed25519-signed audit records, `tests/test_data_protection.py` passes | TLS in transit is asserted in docs only (Render terminates TLS) — not verified; no DB-level / TDE encryption |
| 1.5 | Secrets management | `NOT CERTIFIED` | Environment-injected secrets and a documented rotation procedure exist in `docs/security/SECRETS_MANAGEMENT.md` | No integrated secret-store/rotation automation; cadence values and accountable owners remain unapproved placeholders, and deployment-side protection/rotation has not been verified |
| 1.6 | Webhook security | `PARTIALLY CERTIFIED` | Constant-time HMAC comparison (`hmac.compare_digest`), trusted-proxy-aware IP resolution (`PESAGUARD_TRUSTED_PROXY_COUNT`), IP/CIDR allowlist, **fail-closed** when unconfigured, webhook signing-secret migration, and current focused source-gate tests pass | The unused unrestricted-source variable has been removed from backend workflow test environments; target Daraja contract and live source-validation behavior remain unverified |
| 1.7 | Rate limiting | `PARTIALLY CERTIFIED` | `rate_limiter.py`: atomic Redis Lua token bucket for multi-worker + in-memory fallback, standard `429` + `Retry-After`/`X-RateLimit-*` headers, records a security event on denial; an HTTP regression now checks quota headers, exact retry delay, and refill recovery | Local in-memory path is tested; distributed Redis concurrency and outage behavior still need target-like integration evidence |
| 1.8 | Security scanning | `PARTIALLY CERTIFIED` | `.github/workflows/dependency-scan.yml` runs pip-audit, blocking Bandit and Safety checks, plus a full-history Gitleaks action; `docker-image.yml` runs Trivy and Dependabot checks dependencies. The repository-wide local Bandit run completed with 0 findings | pip-audit, Safety, Gitleaks, Trivy, and the updated GitHub Actions workflow have not run in this evidence update; dependency and history scan results remain unverified |

## 2. Data

| # | Requirement | Status | Evidence | Gap |
|---|---|---|---|---|
| 2.1 | Integrity | `CERTIFIED` | Alembic migrations with FK/unique/check/NOT-NULL constraints, transaction-scoped writes (`phase1` integrity suites), **32 passed** in the Data chunk (`test_action_audit`, `test_phase1_integrity`, `test_phase1_full_integrity`, `test_lifecycle_integrity`, `test_ground_truth_certification`) | — |
| 2.2 | Idempotency | `CERTIFIED` | `IdempotencyRecord` + `ProcessedTransaction` unique constraints, `derive_idempotency_key`, `tests/test_api_idempotency_contract.py`, load-test gate `idempotency: duplicate_count > 0`; live smoke test proved `STORED` → `DUPLICATE` | — |
| 2.3 | Data quality | `PARTIALLY CERTIFIED` | `data_quality.py`, `dq_rules.py`, `provenance.py`, transactional quarantine, remediation history, and focused quality/route tests; **77 passed, 1 Redis-dependent test deselected** in the latest focused selection | Evidence is local Windows/SQLite. PostgreSQL migration execution, production concurrency, calibrated outlier baselines, target capacity, and live alerting remain unverified |
| 2.4 | Lineage | `PARTIALLY CERTIFIED` | `data_lineage.py` (immutable stage snapshots, schema/pipeline version keys), `action_audit` correlation/trace IDs | `tests/test_live_traceability.py` and `tests/test_phase6_e2e_traceability.py` are skip-gated (`PESAGUARD_LIVE_E2E=1`) and CI never sets it; one traceability test fails outright |
| 2.5 | Immutable audit | `CERTIFIED` | PostgreSQL **append-only triggers** (`action_audit.py:481-495`, `BEFORE UPDATE`/`BEFORE DELETE`), Ed25519 cryptographic signatures, hash-chain verification, audit-key lifecycle (`active`/`revoked`), WORM export (`FileImmutableAuditStore.put_immutable` with content hash), 32 Data-chunk tests pass | Retention of the WORM export target is configuration-dependent |

## 3. Financial correctness

| # | Requirement | Status | Evidence | Gap |
|---|---|---|---|---|
| 3.1 | Reconciliation accuracy | `NOT CERTIFIED` | `reconciliation_engine.py`, `ground_truth_certification.py`, `docs/operations/RECONCILIATION_CERTIFICATION.md`, **19 passed** in the Financial chunk | The repo's own gate requires **human-approved** ground truth (`--approved-by` + `--approval-reference` + `--validated-by`) yielding `certification_ready = 1`, `precision = 1.0`, `recall = 1.0`, `false_positives = 0`, `false_negatives = 0`. **No evidence that an approver has signed off.** Prometheus metrics stay non-ready until then. |
| 3.2 | Duplicate prevention | `CERTIFIED` | Unique constraints (`uq_transaction_scope_trans_id`, `uq_idempotency_request`), load gate `no_duplicate_transactions: distinct_transaction_ids == actual_rows`, **19 passed** | — |
| 3.3 | Exception handling | `PARTIALLY CERTIFIED` | `Discrepancy` + `ReconciliationOutbox` + exception events + DLQ, **19 passed** | `tests/test_features.py` produces **8 errors** seeding discrepancy rows (`NOT NULL constraint failed: discrepancies.tenant_id`) — the exception store is not exercisable in the local environment |
| 3.4 | Transaction lifecycle | `CERTIFIED` | `lifecycle.py` (`transition_transaction`), `TransactionEvent` versioned events, `tests/test_lifecycle_integrity.py` passes, `tests/test_phase1_full_integrity.py` exit gates assert 0 lost / 0 duplicate / 0 corrupt / 0 idempotency failures | — |

## 4. Reliability

| # | Requirement | Status | Evidence | Gap |
|---|---|---|---|---|
| 4.1 | Retries | `CERTIFIED` | `event_bus.RetryPolicy` (bounded exponential backoff → DLQ), `action_audit` retry with `_retry_delay_seconds`, Africa's Talking `SmsRetryPolicy`, outbox retry with lease expiry, **29 passed** in the Reliability chunk | — |
| 4.2 | DLQ | `CERTIFIED` | `DeadLetterEvent` + `EventDeliveryController.dlq`, producer `_fallback_to_dead_letter_queue` when Kafka is down, `DeadLetter` table + replay endpoint, **29 passed** | DLQ depth is only observed at unit level |
| 4.3 | Backpressure | `CERTIFIED` | `event_bus.BackpressureGate` (in-flight cap + consumer-lag cap) wired into `EventDeliveryController`, **29 passed** | — |
| 4.4 | Graceful degradation | `PARTIALLY CERTIFIED` | Health checks classify `ok` / `degraded` / `failed`; `broker_consumer_lag` degrades to an empty snapshot when Kafka is unconfigured; `already_processed` fails **closed** on DB error | Degradation paths are only unit-tested; no live degradation drill |
| 4.5 | Circuit breakers | `CERTIFIED` | `producer.py` `CircuitBreaker` (CLOSED/OPEN/HALF-OPEN, threshold 5, 30 s recovery) around Kafka publish, `communications/providers/router.py` per-provider breakers with failover, `tests/test_phase4_event_bus.py` + `tests/test_communications_intelligence.py` pass | Only Kafka and communications providers are covered; no breaker around PostgreSQL |

## 5. Observability

| # | Requirement | Status | Evidence | Gap |
|---|---|---|---|---|
| 5.1 | Logs | `CERTIFIED` | `logging_utils.py` structured JSON with `request_id`/`correlation_id`/`tenant_id`/`event_id`/`trace_id`, sensitive-value redaction, `tests/test_phase5_observability.py` covers the context contract | — |
| 5.2 | Metrics | `CERTIFIED` | `metrics.py` Prometheus payload + business metrics + multi-worker shared Redis metrics + per-engine query timing, `tests/test_phase6_observability_runtime.py`, `tests/test_alerting_monitoring.py` pass | `metrics.py:541-547` **writes `__meta__.py` into the source tree at import time and raises `RuntimeError("spy-model-v1-0-payload-verified")`** if its content is not `OK` — this block is absent from `HEAD` and `"spy-model"` appears nowhere else in the repository. Requires review. |
| 5.3 | Traces | `PARTIALLY CERTIFIED` | `otel_tracing.py` + `observability.trace_span`, `monitoring/otel-collector.yaml`, `tests/test_observability_otel.py` passes | No OTLP backend reachable in this environment; trace export never observed end-to-end |
| 5.4 | Alerts | `PARTIALLY CERTIFIED` | `monitoring/alerts.yml` + `alertmanager.yml`, `alerting/` templates (EN/SW), **27 passed** in the Observability chunk | `tests/test_phase5_observability.py::test_phase5_alert_rules_cover_required_failure_domains` **fails** — the alert rules do not cover the required failure domains; no Alertmanager delivery was observed live |
| 5.5 | Dashboards | `PARTIALLY CERTIFIED` | `monitoring/grafana-dashboard.json` provisioned | Dashboard is never rendered or validated; whether it matches the emitted metric names is unverified |

## 6. Performance

| # | Requirement | Status | Evidence | Gap |
|---|---|---|---|---|
| 6.1 | Load | `PARTIALLY CERTIFIED` | `operations/run_endpoint_load_test.py` drives an HTTP endpoint with bounded load, stress, spike, and soak profiles; records status, transport, throttle, latency, and throughput metrics; and requires all four explicit thresholds before marking a run as an SLO acceptance result. Loopback integration tests pass. `operations/run_postgres_load_test.py` remains a synthetic table benchmark | No target-like PesaGuard transaction/reconciliation benchmark has run. No representative dataset, approved SLO, resource/saturation evidence, or reproducible benchmark artifact is available. The historical load artifact is absent and cannot be independently verified |
| 6.2 | Stress | `NOT VERIFIED` | — | **No stress harness, script, result or definition exists anywhere in the repository** (`grep stress` matches only documentation boilerplate) |
| 6.3 | Spike | `NOT VERIFIED` | — | **No spike harness or result exists.** "Spike" appears only in the fraud-alert runbook and an anomaly classifier test |
| 6.4 | Soak | `NOT VERIFIED` | — | **No soak harness or result exists.** `grep soak` matches only agent guidance documents |

## 7. Recovery

| # | Requirement | Status | Evidence | Gap |
|---|---|---|---|---|
| 7.1 | Backup | `PARTIALLY CERTIFIED` | `pesaguard_backend_pipeline/backup_postgres.py` (compressed `pg_dump`, SHA-256 manifest, `PESAGUARD_BACKUP_ENCRYPT_COMMAND`, off-site upload, retention pruning, `last_status.json`), `tests/test_backup_integrity.py`, `tests/test_phase8_backup_dr.py`, restored-drill CI job (self-contained PostgreSQL); **26 passed** in the Recovery chunk | Previously the test suite had 7 failures in the Recovery chunk — all resolved: PITR tests updated to use `.tar` base-backup artifacts, `test_bad_migration_recovery` corrected, and the `UnboundLocalError` at `test_phase8_backup_dr.py:269` fixed |
| 7.2 | Restore | `PARTIALLY CERTIFIED` | `pesaguard_backend_pipeline/operations/validate_restore.py` validates 12 integrity queries covering record counts, constraints, indexes, audit history, reconciliation rows, fraud assessment storage, and transaction integrity; `operations/restore_drill.py` orchestrates restore + validation; `tests/test_disaster_recovery.py` **4 passed** | An actual restore into a real PostgreSQL instance was not executed here — the `restore-drill` CI job now automates this with a self-contained PostgreSQL container |
| 7.3 | DR | `NOT VERIFIED` | `docs/operations/DISASTER_RECOVERY.md` defines **7 failure drills** and an explicit exit gate; runbook at `docs/runbooks/DISASTER_RECOVERY.md` covers all 7 scenarios; automated restore-drill CI job | No drill artifact from production has been recorded — no restore-validation JSON, no `last_status.json`, no recorded start/end timestamps from a live environment |
| 7.4 | RPO | `NOT VERIFIED` | RPO unified to *near-zero for committed financial transactions* across `docs/operations/DISASTER_RECOVERY.md` and `docs/architecture/DISASTER_RECOVERY.md`; achieved via WAL archiving (`archive_mode=on`) + daily base backup | No measurement has been recorded against a live production database |
| 7.5 | RTO | `NOT VERIFIED` | RTO unified to *less than 15 minutes* across both DR docs; validated by the automated restore-drill CI job | No measurement has been recorded from a live production failover |

## 8. Resilience

| # | Requirement | Status | Evidence | Gap |
|---|---|---|---|---|
| 8.1 | Chaos tests | `PARTIALLY CERTIFIED` | `tests/test_communications_chaos.py` — provider timeouts, provider 500s, provider 429s, duplicate/out-of-order webhooks, worker crashes, database slowdowns; passes alongside the Reliability chunk (29 passed) | Chaos is **in-process fault injection only** (fake clients, `time.sleep`). No chaos runs against real Kafka/PostgreSQL/Redis/network |
| 8.2 | Dependency failures | `PARTIALLY CERTIFIED` | Provider router failover with per-provider circuit breakers; `error_categories.py` classifies `PROVIDER_UNAVAILABLE`/`NETWORK_ERROR`/`TIMEOUT` | `run_postgres_load_test.py` advertises a `failure_injection` block, but **only `slow_query` is implemented** — `database_connection`, `pool_exhaustion`, `redis_unavailable` and `kafka_unavailable` are **hardcoded to `False`** (line 316), so those resilience claims are not exercised by any harness |
| 8.3 | Worker failures | `CERTIFIED` | Outbox leasing with expiry, `tests/test_communications_chaos.py::test_worker_crash_releases_lease_for_retry`, `test_phase1_full_integrity.py::test_worker_crash_leaves_outbox_leasable_and_no_duplicate` | — |
| 8.4 | DB failures | `PARTIALLY CERTIFIED` | `already_processed` fails **closed** on `SQLAlchemyError`; connection-loss recovery in `already_processed` | The database-failure injection mode is not implemented (see 8.2) |
| 8.5 | Queue failures | `CERTIFIED` | `DeadLetter` table, outbox claim/retry/release, `tests/test_transaction_outbox.py` passes | — |

---

## Historical 2026-09-17 tally

| Status | Count |
|---|---|
| `CERTIFIED` | **15** |
| `PARTIALLY CERTIFIED` | **17** |
| `NOT CERTIFIED` | **3** |
| `NOT VERIFIED` | **6** |
| `BLOCKED` | 0 *(recorded as `NOT VERIFIED` — see B11)* |
| **Total items** | **41** |

## Blocker register

| # | Blocker | Proof |
|---|---|---|
| **B1** | **Resolved locally (2026-10-14).** The current security checklist has no merge markers or contact placeholder, and its `SECRETS_MANAGEMENT.md` reference exists. | Current file inspection; this does not close the separate secrets-management/rotation gate B7 |
| **B2** | **The suite is not green.** 40 failed + 1 error (down from 40 failed + 9 errors after the `test_features.py` and `metrics.py` repairs). | `pytest -q --tb=no` → `40 failed, 266 passed, 7 skipped, 6 warnings, 1 error in 125.57s` |
| **B3** | **The dashboard frontend does not exist, and CI hides it.** 21 of the 41 non-green tests (40 failed + 1 error) are frontend tests. They read `pesaguard_backend_pipeline/frontend/communications/*`, which does not exist. Note that `pesaguard-web/` **is** present, but it is a separate Next.js marketing/documentation site — it is not the operations dashboard and does not satisfy these tests. CI's frontend job is guarded by `if [ -f frontend/package.json ]` (repo-root path, also absent) **and** sets `continue-on-error: true`, so npm audit and frontend tests are silent no-ops. | `Test-Path pesaguard_backend_pipeline/frontend/communications` → `False`; failure list: `test_frontend_dashboard.py` 13 + `test_frontend_architecture.py` 8 |
| **B4** | **Reconciliation accuracy is not certified by the repository's own definition.** | `docs/operations/RECONCILIATION_CERTIFICATION.md` requires human approval and `certification_ready = 1`, `precision = 1.0`, `recall = 1.0`, `FP = FN = 0`. No approval artifact exists |
| **B5** | **Harness capability implemented; target-like capacity evidence remains missing.** | `operations/run_endpoint_load_test.py` implements bounded stress, spike, and soak profiles and per-request Daraja HMAC signing; `tests/test_endpoint_load_test.py` exercises it over loopback HTTP. No representative target run or result artifact exists |
| **B6** | **RPO/RTO are unmeasured and mutually contradictory** across three documents | See 7.4 / 7.5 |
| **B7** | **Secrets-management operation is not verified.** A generic rotation procedure is documented, but secret-store integration is absent and the cadence/owner placeholders remain unresolved. | `docs/security/SECRETS_MANAGEMENT.md`; deployment evidence and named owner approval still required |
| **B8** | **No DR drill has ever been executed or evidenced.** | No `restore-validation.json`, no `last_status.json`, no drill output |
| **B9** | **Resolved locally (2026-10-14).** Data-quality/provenance integration is covered by the focused test suites, including remediation migration and transaction-route quarantine. | Current focused run: 77 passed, 1 deselected. PostgreSQL migration, concurrency, and live recovery verification remain blocked |
| **B10** | **No longer present in the current metrics implementation (checked 2026-10-14).** The current module defines the data-quality metrics without the reported source-tree write or marker string. | Current source inspection and marker search; re-run the complete static/security scan before closing the release gate |
| **B11** | **PostgreSQL / Redis / Kafka behavior is unverified in this environment.** Docker CLI is present, but the Linux engine is unavailable; no live dependency or deployment checks were run. | Local Docker API probe failed; `psql` is unavailable; target-like verification remains required |
| **B12** | **Live integration E2E remains unverified.** CI now enables the PostgreSQL integration flag and sets the non-bypass application-role URL, but no GitHub Actions result after that change exists. `PESAGUARD_LIVE_E2E` remains disabled because no approved gateway, broker, or Alertmanager sandbox is configured | Current workflow source; no post-change Actions run |

## Required human actions before re-certification

1. **Re-run and classify the frontend tests** (B3) against the current frontend
   layout. If the historical failures remain, either restore the expected
   operations dashboard or retire/update the tests and make the CI scope
   explicit without masking failures.
2. **Re-evaluate the historical full-suite failures** (B2): the 2026-09-17
   record listed 40 failures and one error; this suite was not rerun in the
   current environment.
3. **Rebuild the failure list from a current full-suite run** (B2). The
   following grouping describes the 2026-09-17 run only: 21 were the missing frontend (B3);
   `test_phase8_backup_dr.py` contributes 7 (including `test_wal_archive_command_is_configured`,
   which needs PostgreSQL); `test_localization_fallback.py` 2; `test_backend_features.py` 2
   (line 119 imports `pesaguard_backend_pipeline.scheduled_reports` while the module lives at
   `pesaguard_backend_pipeline/operations/scheduled_reports.py`); `test_dashboard_features.py` 2;
   `test_escalation_actions.py` 2; `test_retention_cleanup.py`, `test_redis_cache.py`,
   `test_phase5_observability.py`, `test_phase6_e2e_traceability.py` 1 each. The
   `test_features.py` errors are already fixed (see remediations above).
4. **Execute the reconciliation certification** (B4): run
   `ground_truth_certification.py register` with a real approver and reference, then
   `validate`, and confirm `certification_ready = 1` with precision/recall = 1.0.
5. **Run the stress, spike and soak profiles** (B5) against an approved,
   production-shaped PostgreSQL/Redis/Kafka stack with a representative
   dataset and approved SLOs. Preserve the JSON results and resource metrics.
6. **Execute all 7 documented DR drills** (B8) and commit
   `restore-validation.json` plus `last_status.json`, with measured RPO and RTO.
7. **Reconcile the RPO/RTO targets** (B6) into one authoritative pair of numbers
   before any drill can be judged.
8. **Select and integrate a real secret store with a rotation procedure** (B7).
9. **Run the updated CI workflow** (B11/B12) with PostgreSQL and Redis, verify
    the PostgreSQL race/RLS tests pass, and preserve the Actions result. Keep
    `PESAGUARD_LIVE_E2E` gated until approved provider, broker, and monitoring
    sandboxes and credentials are available.

## Sign-off

| Role | Name | Decision (APPROVE / REJECT) | Date |
|---|---|---|---|
| Engineering lead | | | |
| Security owner | | | |
| Finance / data owner | | | |

> **This document certifies nothing by itself.** Production readiness may only be
> claimed when every row above is `CERTIFIED`, the blocker register is empty, and the
> sign-off table is completed by a named human owner for each domain.
