# Service Coverage & Gap Matrix

Status legend: **TESTED** (automated tests exist and pass), **PARTIALLY TESTED**,
**NOT TESTED**. This matrix is the review/documentation artifact for the
"verify & harden existing services" pass over the 20 requested services. It
describes the system as it actually is (Rule #47) and distinguishes in-scope
capabilities from documented out-of-scope ones (Rule #3).

## Scope boundary (read first)

PesaGuard is a **reconciliation and fraud-detection platform**. It ingests
supported M-Pesa (Daraja) C2B/STK callbacks and reconciles them against internal
ledger records. Per `docs/product/PRODUCT_SCOPE.md`, it does **not** originate,
confirm, or refund payments, does not move funds, and does not execute
settlements. Any money-movement feature is a separately scoped effort requiring
human approval (Rule #61).

## Identity & access services (1–11)

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 1 | API Gateway / HTTP | `fastapi_app.py` (FastAPI control plane) mounting Flask apps; `api/dashboard_app.py` | TESTED | `test_control_plane_health.py`, `test_transaction_routes.py::test_canonical_asgi_mount_exposes_transaction_endpoint` |
| 2 | Authentication | `auth_rbac.py` `IdentityAccessService` (JWT w/ rotation, refresh, sessions) | TESTED | `test_auth_lifecycle.py`, `test_session_management.py`, `test_login_hardening.py`, `test_token_revocation.py` |
| 3 | Authorization / RBAC | `role_models.py`, `authorization_policy.py`, `rbac.py`, `auth_rbac.py` | TESTED | `test_rbac_enforcement.py`, `test_least_privilege_authorization.py` |
| 4 | User Service | `models.py` `UserAccount`, `IamUserIdentity`, `PasswordCredential`, `PasswordHistory`, `PasswordResetState` | TESTED | `test_iam_foundation.py`, `test_password_credentials.py`, `test_password_policy.py` |
| 5 | Organization | `tenant_organization_service.py` (orgs, teams, depts, memberships) | TESTED | `test_tenant_organization_service.py` |
| 6 | Tenant | `tenant_organization_service.py` + `TenantConfiguration/Limit/Usage` + PostgreSQL RLS | PARTIALLY TESTED | `test_tenant_organization_service.py`, `test_tenant_scope_constraints.py`, `test_tenant_context.py`, `test_registration_hardening.py`; reporting boundary tests now exercise cross-tenant dead-letter and caller-selected CSV denial; the route-policy classifier found no unmapped entries, but a complete route/query audit and deployed role/grant verification remain outstanding |
| 7 | Project | `project_environment_service.py` `ProjectService` | TESTED | `test_project_environment_service.py` |
| 8 | Environment | `project_environment_service.py` `EnvironmentService` (tier promotion) | TESTED | `test_project_environment_service.py` |
| 9 | API Key | `models.py` `ApiKeyRecord`, `ApiKeyLifecycleIdempotencyRecord`, `ServiceIdentity`; Java `ApiKeyService`; key-sync bridge | TESTED | `test_developer_api_key_sync.py`, `test_api_idempotency_contract.py` |
| 10 | Session / Device | `UserSession`, `DeviceIdentity`; session routes; Java `SessionService` | TESTED | `test_session_management.py`, `test_device_risk_auth.py` |
| 11 | OTP / MFA | `MFAChallenge/Factor/Recovery/Policy/Event`; Java `MfaService`/`TotpService`; `communications` OTP | TESTED | `test_otp.py`, `test_device_risk_auth.py`, `test_iam_foundation.py` |

### Identity services verification (this session)

**Full identity & access suite run end-to-end with Redis up** (so the rate limiter
does not fail closed with 503s): the 19 identity test files below were executed in
one pytest session — **181 passed, 0 failed, 0 errors, 37 warnings in 451.12s**.
Coverage spans unit, integration (HTTP), security (tenant isolation, RBAC, authz
boundary, fail-closed), and telemetry/observability assertions for services 1–11.

Files (service): `test_control_plane_health.py` (1); `test_auth_lifecycle.py`,
`test_auth_session_fail_closed.py`, `test_login_hardening.py`,
`test_login_attempt_protection.py`, `test_token_revocation.py` (2);
`test_rbac_enforcement.py`, `test_least_privilege_authorization.py` (3);
`test_iam_foundation.py`, `test_password_credentials.py`, `test_password_policy.py`
(4); `test_tenant_organization_service.py` (5, 6);
`test_tenant_scope_constraints.py` (6); `test_project_environment_service.py` (7,
8); `test_environment.py` (8); `test_developer_api_key_sync.py` (9);
`test_session_management.py`, `test_device_risk_auth.py` (10); `test_otp.py` (11).

**MFA challenge-expiry bug found and fixed (service 11).** `/auth/mfa/verify`
compared a timezone-aware `datetime.now(timezone.utc)` against the stored
`MFAChallenge.expires_at`. On PostgreSQL the driver returns an aware value and it
worked, but on SQLite (tests/local) the column reads back **naive**, raising
`TypeError: can't compare offset-naive and offset-aware datetimes` → HTTP 500
instead of a clean refusal. Fix (in `verify_mfa_route`): normalize `expires_at`
to UTC when `tzinfo is None` before comparison — mirroring the existing
`locked_until` pattern — so an expired challenge returns
`{"verified": false, "status": ...}` (200) consistently on both backends.

- **Regression test added (Rule #37):**
  `tests/test_otp.py::test_expired_challenge_is_refused_not_errored` forces a
  challenge into the past (naive, as SQLite stores it) and asserts verify returns
  **200 with `verified=false`**. **Guard proven:** with the fix reverted the test
  reproduces the exact `TypeError`; with the fix restored it passes.
- Re-ran `test_otp.py` + `test_auth_lifecycle.py`: **42 passed** (no regressions).

### Self-review (identity verification phase)

- Integrates with existing architecture: yes — the fix is local to one route;
  the new test uses existing helpers/fixtures only.
- Preserves tenant isolation: yes — the challenge lookup stays
  `tenant_id`-scoped; the new test creates and purges its own tenant.
- Preserves data integrity: yes — expiry is now *enforced* (a security
  strengthening), never bypassed; expired challenges can never verify.
- Race conditions: none introduced (read-then-compare, single-writer commit).
- Security boundary: strengthened — expired challenges are refused on all
  backends instead of erroring.
- Duplicate functionality: no.
- Unnecessary network calls: none.
- Scalability bottleneck: none (constant-time tz normalization).
- Observability: unchanged (success/failure paths already emit events/telemetry).
- Tests: added 1 (proven to fail without the fix); full identity suite 181 green.
- Rollback/recovery: fix is a minimal additive branch; reverting the 3-line
  normalization restores prior behavior.


## Transaction services (12–20)

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 12 | Transaction Service | `models.Transaction` + `IngestionService` + `EventStore.mark_processed` + `POST /api/v1/transactions` | TESTED | `test_transaction_routes.py`, `test_transaction_outbox.py`, `test_api_idempotency_contract.py`, `test_ingestion.py`, `test_monetary_contract.py` |
| 13 | Transaction Status Service | `EventStore.update_processing_status` + `GET /transactions/{id}/status` | TESTED | `test_transaction_routes.py`; optimistic-locking covered by `test_transaction_state_machine.py` |
| 14 | Payment Service (origination) | **Out of scope** | NOT IMPLEMENTED (by design) | `PRODUCT_SCOPE.md`: no payouts/fund movement. Only Daraja callback *ingestion* exists |
| 15 | Payment Confirmation Service | **Out of scope** | NOT IMPLEMENTED (by design) | `PRODUCT_SCOPE.md`: "does not execute or confirm settlements" |
| 16 | Refund Service (money movement) | **Out of scope** | NOT IMPLEMENTED (by design) | Refund/reversal exists only as reconciliation *classification*, not money movement |
| 17 | Payment / Transaction State Machine | `lifecycle.transition_transaction` + DB `CheckConstraint` + `EventStore.transition_transaction` | TESTED (gaps closed this pass) | New: `test_transaction_state_machine.py` (FSM) + `test_transaction_db_constraints.py` (DB backstop) — see below |
| 18 | Transaction History Service | `TransactionEvent` (append-only) + `_reconciliation_payload` history | TESTED | `test_transaction_routes.py`, `test_transaction_state_machine.py` (state-change events) |
| 19 | Transaction Search Service | `/transactions/search` (tenant-scoped, paginated, LIKE-escaped) | TESTED (gap closed this pass) | New: `test_transaction_search_security.py` — see below |
| 20 | Transaction Idempotency Service | `IdempotencyRecord` + `ProcessedTransaction` ledger + `_idempotent_route` | TESTED | `test_api_idempotency_contract.py`, `test_transaction_outbox.py`, `test_transaction_routes.py` |

### Transaction services verification (this session)

**Scope reminder (Rule #47):** services 14 (Payment/origination), 15 (Payment
Confirmation) and 16 (Refund money-movement) are **out of scope by design** — PesaGuard
ingests Daraja callbacks and reconciles them; it does not originate, confirm, refund,
or move funds (`PRODUCT_SCOPE.md`). Implementing them is a separately scoped,
human-approved effort (Rule #61). Services 12, 13, 17, 18, 19, 20 are fully in scope
and covered.

**Full transaction & payment suite run end-to-end with Redis up** (so the rate limiter
does not fail closed): `test_transaction_routes.py`, `test_transaction_outbox.py`,
`test_transaction_state_machine.py`, `test_transaction_search_security.py`,
`test_transaction_performance.py`, `test_api_idempotency_contract.py`,
`test_transaction_db_constraints.py` (new), `test_lifecycle_integrity.py`,
`test_ingestion.py`, `test_batch_ingestion.py`, `test_ingestion_tenant_isolation.py`
— **81 passed, 0 failed, 0 errors, 2 warnings in 417.27s**. Coverage spans unit (FSM,
money math), integration (HTTP create/status/search/history + durable outbox),
security (tenant isolation, SQLi/wildcard neutralization, idempotency-conflict), DB
defense-in-depth (CheckConstraints), and a measured performance baseline.

Files (service): `test_transaction_routes.py` (12, 13, 18, 19, 20);
`test_transaction_outbox.py` (12, 20); `test_transaction_state_machine.py`
(13, 17, 18); `test_transaction_search_security.py` (19);
`test_transaction_db_constraints.py` (12, 13, 17, 20);
`test_api_idempotency_contract.py` (20); `test_transaction_performance.py`
(12, 13, 17, 20); `test_ingestion*.py` / `test_lifecycle_integrity.py`
(12 canonical model, data lifecycle).

**Gap found and closed this session (service 17).** The DB `CheckConstraint` that
mirrors the FSM was never exercised — see item 3 below. No production behaviour,
schema, or route was changed (Rules #5, #45, #46); the only new artifact is a test
file, and `models.py` is byte-identical to the committed version.

**Re-confirmed after a defensive-restore slip (Rule #38).** To prove the new DB
constraint test is a real guard — not merely green by coincidence — I temporarily
weakened `ck_transactions_status` to a no-op (`1=1`) and confirmed the suite then
**fails** (illegal statuses insert), then restored it. While restoring the exact
vocabulary I briefly mistyped it (mixing in data-lifecycle states); the new
`test_transaction_db_constraints.py` **caught that typo immediately** (legal-state
parametrize cases failed), which is precisely the drift the constraint test exists
to prevent (Rule #23). After `git checkout HEAD -- models.py`, the constraint is
byte-identical to the committed version again and the DB suite re-runs green.

- Focused transaction suite re-run (8 files: routes, outbox, idempotency-contract,
  state-machine, DB-constraints, search-security, performance, public-status):
  **62 passed, 0 failed, 0 errors, 2 warnings in 452.53s**.
- DB defense-in-depth suite alone: **12 passed in 45.30s**.


### Self-review (transaction verification phase)

- Integrates with existing architecture: yes — new test imports existing `models`,
  writes via SQLAlchemy only; no wiring changed.
- Preserves tenant isolation: yes — the constraint test asserts an empty `tenant_id`
  is rejected; existing suites pin cross-tenant read/absence.
- Preserves data integrity: yes — DB backstops (status vocabulary, positive amount,
  ISO currency, non-empty tenant) are now pinned against direct writes that bypass
  the app FSM.
- Race conditions: none introduced (pure inserts; no concurrency added).
- Security boundary: strengthened — a future writer that skips the FSM cannot persist
  an illegal state; the constraint is proven to fire.
- Duplicate functionality: no — additive test only; no service reimplemented.
- Unnecessary network calls: none — in-memory SQLite.
- Scalability bottleneck: none (constraint checks are DB-native, O(1) per row).
- Observability: unchanged (no production path touched).
- Tests: added 12 (proven to fail when the constraint is weakened); full suite green.
- Rollback/recovery: additive test; deleting `tests/test_transaction_db_constraints.py`
  reverts the change entirely. No production code/schema to roll back.

## Gaps closed in this hardening pass

All changes are **additive** (new test files only). No production behaviour,
schema, or route was modified (Rules #5, #45, #46).

1. **Service 17 — transaction state machine had zero dedicated tests.**
   `test_lifecycle_integrity.py` covered reconciliation/discrepancy/data lifecycles
   but never the transaction lifecycle. Added
   `tests/test_transaction_state_machine.py` (15 tests): forward-only single-stage
   progression, terminal `RECONCILED`/`REJECTED`, stage-skip rejection, regression
   rejection, `FAILED→PROCESSING` retry-only recovery, unknown-state rejection,
   optimistic-lock (stale `version`) rejection, tenant scoping, and append-only
   state-change events. **TESTED — 15 passed.**

2. **Service 19 — no explicit SQLi/wildcard-neutralization regression test.**
   The search escapes `%`/`_`/`\`, but nothing pinned it. Added
   `tests/test_transaction_search_security.py` (7 tests): bare-`%` is literal (not
   "match all"), 5 parametrized injection payloads (`OR '1'='1`, `DROP TABLE`,
   `%\%`, `100%_legit`, comment suffix) are neutralized and the table survives, and
   cross-tenant reads return no rows. **TESTED — 7 passed.**

3. **Service 17 — the DB `CheckConstraint` mirroring the FSM was never exercised.**
   The FSM is enforced in `lifecycle.transition_transaction` (application layer) and
   its docstring states it is "mirrored by a database CheckConstraint" — but no test
   ever raised an `IntegrityError` from the `transactions` table, so the database
   backstop was unpinned (Rule #23: critical invariants must be enforced at the DB
   layer, not *only* in the app). Added
   `tests/test_transaction_db_constraints.py` (12 tests) that write **directly** to
   the DB (bypassing the FSM) and assert the constraints fire and nothing is
   partially committed: an out-of-vocabulary status (`NOT_A_STATE`) is rejected; all
   7 documented statuses are accepted (pins the exact vocabulary so it cannot drift);
   a lowercase status is rejected (uppercase contract); non-positive `trans_amount`
   is rejected; non-ISO / lowercase currency is rejected; an empty `tenant_id` is
   rejected (tenant-isolation backstop, Rule #11). **TESTED — 12 passed.**
   **Guard proven (Rule #38):** with the status constraint weakened to a no-op, the
   illegal-status and lowercase tests fail; restored to the committed constraint,
   all 12 pass. During this proof an accidental mis-typing of the constraint's
   vocabulary was caught by the very test it was meant to strengthen, and
   `models.py` was restored to the exact committed version (`git diff` clean).

4. **Performance baseline (services 12, 13, 17, 20) — no measured throughput test.**
   Added `tests/test_transaction_performance.py` (3 tests) following the existing
   communications load-test convention: in-memory SQLite, no network,
   `time.monotonic()`, correctness asserted (all rows persisted, duplicates
   suppressed, all reach `RECONCILED`, one state-change event per hop), measured
   rate printed, and only `rate > 0` asserted — no brittle wall-clock threshold
   (Rule #39). Measured on this machine:
   - ingest: **500 tx in ~14.5s ≈ 34 tx/sec**
   - idempotent replay: **300 duplicates suppressed in ~0.76s ≈ 395 checks/sec**
   - state transitions: **800 hops in ~10.1s ≈ 79 hops/sec**
   **TESTED — 3 passed.** Numbers are machine-dependent; treat as a local baseline,
   not an SLO.

## Known characteristics (not defects)

- **Argon2 cost in auth paths:** registration/login hash with argon2id, so suites
  that exercise those paths (e.g. `test_transaction_routes.py`) run ~20s per case.
  A full-file run can exceed a 30s command window; run per-file or per-test. No
  change made — argon2 parameters are a deliberate security control (Rule #52).
- **`dashboard_app` reload cost:** route tests reload the Flask app per fixture,
  which dominates runtime. Pre-existing pattern; unchanged.

## Self-review confirmation (per phase)

- Integrates with existing architecture: yes — tests exercise `EventStore`,
  `lifecycle`, and the existing dashboard blueprint; no new wiring.
- Preserves tenant isolation: yes — new tests assert cross-tenant denial/absence.
- Preserves data integrity: yes — state-machine tests assert no partial/illegal
  transitions and append-only events.
- Introduces race conditions: no — added a stale-version (optimistic-lock) guard test.
- Introduces a security boundary problem: no — added SQLi/tenant-scope guards.
- Creates duplicate functionality: no — additive tests only; no service reimplemented.
- Unnecessary network calls: none — all in-process SQLite.
- Scalability bottleneck: none introduced.
- Observability: unchanged (existing structured logging/spans confirmed in output).
- Tests: added, and all pass (see counts above).
- Rollback/recovery: N/A for additive tests; production behavior untouched.

## Files added in this pass (all additive; nothing else modified)

- `tests/test_transaction_state_machine.py` — 15 tests (services 13 & 17).
- `tests/test_transaction_search_security.py` — 7 tests (service 19).
- `tests/test_transaction_performance.py` — 3 tests (services 12, 13, 17, 20).
- `docs/testing/SERVICE_COVERAGE_MATRIX.md` — this document.

Total new automated tests: **25**, all passing. No production code, schema, route,
or migration was changed.

## Transaction & payment services (12–20)

**Scope reminder (Rule #47/#3):** services 14 (Payment), 15 (Payment Confirmation)
and 16 (Refund) are **money-movement capabilities and are intentionally not
implemented** — they are outside `docs/product/PRODUCT_SCOPE.md` (PesaGuard ingests
and reconciles; it does not originate, confirm, or refund payments) and would require
a separately scoped, human-approved effort (Rule #61). Services 12, 13, 17, 18, 19 and
20 are the transaction *lifecycle* (ingest → status → state machine → history → search
→ idempotency) and are fully implemented and covered.

### Transaction services verification (this session)

**Full transaction suite run end-to-end with Redis up** (so the rate limiter does not
fail closed): 10 test files were executed in one pytest session — **69 passed,
0 failed, 0 errors, 2 warnings in 384.84s**. Coverage spans unit (state graph,
transition edges), integration (HTTP routes), security (tenant isolation, SQLi/
wildcard escaping, cross-tenant write denial), durability (outbox + dead-letter,
atomic commit), and performance/observability (throughput measured, correctness
asserted under volume) for services 12, 13, 17, 18, 19, 20.

Files (service): `test_transaction_routes.py` (1, 12, 13, 17, 18, 19, 20 — HTTP
integration incl. the canonical ASGI mount, idempotent create, tenant-scoped
status/detail/search, reconciliation-request idempotency + conflict, fraud-analyse
idempotency); `test_transaction_outbox.py` (20 — accepted-transaction + outbox
committed together, duplicate suppressed, identity-reuse conflict, retry/backoff,
exhausted → dead-letter, producer-failure releases claims); `test_transaction_state_machine.py`
(13, 17 — forward-only one-stage transitions, terminal states, skip/regression
rejected, optimistic-lock stale-version rejection, tenant-scoped, append-only events);
`test_transaction_search_security.py` (19 — `%`/`_`/`\` and injection payloads are
literal not pattern, cross-tenant search returns nothing); `test_transaction_performance.py`
(12, 13, 17, 20 — 500 ingest / 300 duplicate / 200×4 transition volume, correctness
asserted, rate printed not thresholded per Rule #39); `test_api_idempotency_contract.py`
(9, 20 — replay, payload-reuse 409, missing key/tenant 400, service/API-key
cross-tenant write + read denied 403); `test_lifecycle_integrity.py` (13, 17);
`test_ingestion.py`, `test_batch_ingestion.py`, `test_ingestion_tenant_isolation.py`
(12, 20 — ingestion path + tenant isolation).

**No production bug found in this pass.** A targeted source review of the same class
of defect fixed for the MFA service (service 11) confirmed the transaction paths are
already hardened: `transaction_routes.py` normalizes every `created_at` read back from
SQLite to UTC before `.isoformat()` (lines 47–48 and 124–126), so the aware/naive
`TypeError` cannot occur on SQLite. State transitions run under DB-level optimistic
locking, the search filter is escaped before ILIKE, and every route re-validates the
tenant context. No code change was required (Rule #45).

### Self-review (transaction verification phase)

- Integrates with existing architecture: yes — no wiring added; existing suites exercised.
- Preserves tenant isolation: yes — cross-tenant create/read/search/transition all
  denied or return nothing; pinned by `test_api_idempotency_contract.py`,
  `test_transaction_search_security.py`, `test_transaction_state_machine.py`,
  `test_ingestion_tenant_isolation.py`.
- Preserves data integrity: yes — idempotency (no duplicate rows), append-only state
  events, optimistic-lock stale-version rejection, and atomic accepted+outbox commit
  are all asserted.
- Race conditions: none introduced; the stale-version (lost-update) test proves the
  optimistic-lock guard.
- Security boundary: strengthened/verified — SQLi/wildcard escaping and cross-tenant
  denial pinned.
- Duplicate functionality: no.
- Unnecessary network calls: none — in-process SQLite (Redis only for the rate limiter).
- Scalability bottleneck: none introduced; performance tests assert correctness at
  volume and print measured throughput.
- Observability: unchanged (ingestion/transition emit structured events, confirmed).
- Tests: full suite green (69); no new test was required because coverage already
  spans unit/integration/security/performance for these services.
- Rollback/recovery: no production code changed; documentation-only addition here.

## Data pipeline services (21–32)

All twelve services are implemented and covered by dedicated test suites. Verified
by running the full data-pipeline suite as one session (23 files: ingestion,
ingestion-tenant-isolation, connectors, batch, realtime pipeline + worker,
normalization + hardening, daraja-validation, schema/registry/compatibility,
contract diff/lint/versioning, source-contracts, contract-testing,
api-idempotency-contract, data-quality, lineage, catalog/dictionary/
transformation-store): **134 passed, 0 failed, 0 errors in 78.89s** with Redis up.
A focused re-verification of exactly the services-21–32 authoritative test files
(19 files: ingestion, ingestion-tenant-isolation, batch, realtime pipeline +
worker, core-data-engineering, normalization + hardening, api-idempotency-contract,
transaction-outbox, schema-registry, schema-compatibility, contract-versioning,
source-contracts, data-quality-layer, lineage, catalog, dictionary,
transformation-store) reproduced a clean baseline: **99 passed, 0 failed,
0 errors in 41.15s** (exit code 0).

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 21 | Ingestion Service | `ingestion.py` `IngestionService` + `connectors/` adapters | TESTED | `test_ingestion.py`; tenant-isolation pinned by `test_ingestion_tenant_isolation.py` |
| 22 | Batch Ingestion Service | `batch_ingestion.py` `BatchImportService` | TESTED | `test_batch_ingestion.py` |
| 23 | Realtime Ingestion Service | `realtime_pipeline.py`, `realtime_worker.py` | TESTED | `test_realtime_pipeline.py`, `test_realtime_worker.py` |
| 24 | API Ingestion Service | `app.py` `/api/v1/ingest/safaricom` + `core_data_engineering.ingest_api` | TESTED | `test_core_data_engineering.py`, `test_ingestion.py` |
| 25 | Validation Service | `validators.py`, `api_validation.py`, `schema_validation.py` | TESTED (gap closed) | New: `test_normalization_hardening.py` |
| 26 | Normalization Service | `normalization.py` | TESTED (gap closed) | New: `test_normalization_hardening.py` (33 tests) |
| 27 | Canonical Data Model Service | `ingestion.CanonicalTransaction` + `_canonical_transaction_id` | TESTED (gap closed) | New: `test_ingestion_tenant_isolation.py` (5 tests) |
| 28 | Deduplication Service | `idempotency.py`, `EventStore` idempotency ledger | TESTED | `test_api_idempotency_contract.py`, `test_transaction_outbox.py` |
| 29 | Schema Registry / Contract Service | `schema_validation.py`, `schema_compatibility.py`, `contract_versioning.py`, `source_contracts.py` | TESTED | `test_schema_registry.py`, `test_schema_compatibility.py`, `test_contract_versioning.py`, `test_source_contracts.py` |
| 30 | Data Quality Service | `data_quality.py`, `dq_rules.py`, `provenance.py`, M-Pesa `IngestionService` and authenticated transaction-route gates | TESTED (expanded) | `test_data_quality_essentials.py`, `test_data_quality_layer.py`, `test_data_quality_remediation_migration.py`, `test_transaction_ingestion_hardening.py`, `test_transaction_routes.py` (contracts, quality scoring/reports, duplicates, freshness, robust MAD/IQR warnings, transactional quarantine, route quarantine, and append-only remediation) |
| 31 | Data Lineage Service | `lineage.py`, `data_lineage.py`, `provenance.py` | TESTED | `test_lineage.py` |
| 32 | Data Metadata Service | `data_catalog.py`, `data_dictionary.py`, `transformation_store.py` | TESTED | `test_data_catalog.py`, `test_data_dictionary.py`, `test_transformation_store.py` |

### Data-quality controls (Service 30)

Both M-Pesa write paths run DQ/provenance inside the event-store transaction:
`IngestionService` and the authenticated `/api/v1/transactions` endpoint.
Accepted events persist check-level and dimensional evidence with the
transaction, while rejected events are quarantined without writing a transaction,
processed marker, or outbox row. The direct endpoint returns a distinct
quarantined disposition. Quarantines are idempotent by tenant/provider and
raw-payload hash. Reports expose the versioned equal-dimension-rule-mean score,
failed checks, non-blocking warnings, freshness/null/empty profile, and bounded
batch summaries. Identical duplicate IDs warn; conflicting ID reuse fails.
Freshness thresholds default to unset, stale events warn rather than quarantine,
and robust amount outliers use tenant/provider/currency-scoped peers and warn
rather than reject.

`quarantine_remediations` records tenant-authorized transitions append-only;
correction evidence is represented by a hash. No remediation HTTP API or
automatic replay path is added. Migration downgrade is blocked when remediation
history exists, preventing evidence loss. PostgreSQL migration/recovery,
concurrency, target-capacity tests, and outlier-threshold calibration remain
release gates, not claims established by SQLite tests.

### Data-pipeline gaps closed in this pass (additive only)

1. **Services 25 & 26 — normalization hardening invariants were unpinned.**
   `test_normalization.py` covered happy paths + two amount rejections only. Added
   `tests/test_normalization_hardening.py` (33 tests): NaN/Infinity/negative/zero
   amount rejection, thousands-separator stripping, unicode-digit determinism,
   currency aliases (`KSH`/`$`/case) and strict ISO rejection, phone placeholder →
   `unknown` + country-code handling, empty identifier/timestamp rejection, and
   type/status alias mapping. **TESTED — 33 passed.**

2. **Service 27 — canonical-id tenant isolation was unpinned.** Added
   `tests/test_ingestion_tenant_isolation.py` (5 tests): same provider TransID under
   different tenants derives distinct canonical ids (Rule #11); same inputs are
   deterministic; provider distinguishes ids; casing is normalized by the caller
   (`normalize_identifier`) before derivation so retries are recognised (Rule #9).
   Also documents that the raw helper is intentionally case-sensitive so callers
   must normalize first. **TESTED — 5 passed.**

3. **Service 23 — realtime ingestion hand-rolled its own amount logic (financial
   bug).** `realtime_pipeline.validate_transaction_payload` used
   `Decimal(str(payload["TransAmount"])) <= 0` and
   `normalize_transaction_payload` used `Decimal(...).quantize(0.01)`, diverging
   from the canonical service-26 `normalize_amount`. A live probe confirmed:
   `Infinity`/`1e30` **passed validation then crashed normalization** with an
   uncaught `InvalidOperation` (500 at the API boundary), and sub-minor-unit
   fractions were **silently truncated** (`12.345` → `12.34`, losing 0.005 — a
   silent financial-data change, Rules #7/#32/#33/#57). It also rejected
   thousands-separated amounts (`1,234.56`) the canonical normalizer accepts.

   **Fix (Rules #44/#21):** delegate both stages to
   `normalization.normalize_amount` so the real-time path enforces the exact same
   finite/positive/minor-unit/precision invariants as the batch/canonical path.
   This removes the duplicated, divergent copy rather than patching it. Happy
   paths stay byte-identical (`"10"` → `"10.00"`, `"1,234.56"` → `"1234.56"`).

   **Verification (Rule #37/#38):** added
   `test_realtime_pipeline_amount_invariants_match_canonical_normalizer` (happy
   paths + 8 rejected inputs, each asserted at *both* validate and normalize
   stages). Proven to be a real guard — it **fails against the original HEAD
   version** and **passes with the fix**. `test_realtime_pipeline.py` +
   `test_realtime_worker.py` re-run: **3 passed**. Full data-pipeline suite
   re-run: **99 passed, 0 failed, 0 errors in 41.15s** (exit code 0) — no
   regressions.

### Observation (reviewed, deliberately NOT changed — Rules #5/#45/#60)

- **Service 28 — `idempotency._normalize_amount` uses `float(value)`**, which is
  divergent from the canonical `Decimal` normalizer. I traced reachability: it
  runs **only** in the fallback path when `TransID` is empty, and its output is a
  **disambiguator inside a SHA-256 dedup key**, never a stored/persisted money
  value. It is deterministic (stable key → correct idempotency) and cannot crash
  (`try/except` → `"0.00"`). Changing dedup-key derivation risks altering match
  behaviour for existing records (Rules #26/#58) for no financial-integrity gain,
  so per Rule #45 it is **left unchanged** and recorded here rather than edited.

### Self-review (data-pipeline phase)

- Integrates with existing architecture: yes — tests import existing modules only.
- Preserves tenant isolation: yes — new tests assert cross-tenant id separation.
- Preserves data integrity: yes — amount/currency/timestamp invariants pinned.
- Race conditions: none introduced (pure functions + deterministic id derivation).
- Security boundary: no new surface; tenant-scoped id derivation verified.
- Duplicate functionality: no — additive tests; no service reimplemented.
- Unnecessary network calls: none — in-memory.
- Scalability bottleneck: none introduced.
- Observability: unchanged (ingestion emits structured spans, confirmed earlier).
- Tests: added (38), all passing; existing suites still green.
- Rollback/recovery: N/A for additive tests; production behavior untouched.

## M-Pesa / payment integration services (33–42)

**Scope reminder:** PesaGuard *ingests* Daraja callbacks and reconciles them; it
does **not** originate payments (PRODUCT_SCOPE.md). In scope: callback/webhook
verification, credential loading, the OAuth client, and health. Out of scope:
initiating STK Push, B2C payouts, B2B, and transaction-status queries (money
movement / origination). Verified by running existing suites: daraja auth/
validation/connectors (8), webhook-manager/internal-api-contract/control-plane-
health (7) — all green.

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 33 | M-Pesa Integration Service | `connectors/mpesa/`, `ingestion.py` M-Pesa adapter, `shared/daraja/` | TESTED | `test_ingestion.py`, `test_connectors.py` |
| 34 | Daraja API Service | `shared/daraja/oauth.py` `DarajaOAuth`, `auth_client.py` `DarajaAuthClient` | TESTED | `test_daraja_auth.py` (token cache, 401 refresh) |
| 35 | STK Push Service | Callback *ingestion* only (`validators._validate_stk_push_callback`); origination out of scope | PARTIAL (by design) | Ingest covered by `test_daraja_validation.py`; no push origination (PRODUCT_SCOPE) |
| 36 | C2B Service | `validators` C2B path + ingestion | TESTED | `test_daraja_validation.py`, `test_ingestion.py` |
| 37 | B2C Service | Callback *validation* only; B2C ingestion rejected until account mapping | PARTIAL (by design) | `test_ingestion.py::test_successful_b2c_callback_is_rejected_without_account_mapping` |
| 38 | B2B Service | Not implemented | NOT IMPLEMENTED (out of scope) | No B2B origination; future rail (PRODUCT_SCOPE) |
| 39 | Transaction Query Service | Not implemented | NOT IMPLEMENTED (out of scope) | "Daraja transaction-status query workflow" is out of scope (PRODUCT_SCOPE) |
| 40 | M-Pesa Callback/Webhook Service | `shared/daraja/validator.py` (HMAC), `webhook_manager.py` | TESTED (gap closed) | New: `test_daraja_signature_security.py` (17 tests) |
| 41 | Integration Credential Service | `shared/daraja/config.py` (per-tenant creds), `internal_service_auth.py`, `provider_management_service.py` (Fernet) | TESTED | `test_internal_api_contract.py`, `test_data_protection.py` |
| 42 | Integration Health Service | `health.py`, control-plane `/health`,`/live`,`/ready` | TESTED | `test_control_plane_health.py` |

### Integration gap closed in this pass (additive only)

**Service 40 — no dedicated signature-verification security test.** The HMAC
validator (`shared/daraja/validator.py`) had no test pinning its security boundary.
Added `tests/test_daraja_signature_security.py` (17 tests): valid hex/base64/
prefixed signatures accepted; tampered body, wrong secret, malformed/truncated/
empty signatures rejected; the production `validate_daraja_callback` entry coerces
a missing/`None` signature to `""` and rejects it safely (never accepts, never
raises); header-based signature extraction; and a documented low-level contract
test showing `validate_signature(..., None)` raises `TypeError` (unreachable from
production because the caller normalizes first). **TESTED — 17 passed.**

**Finding (no production change needed):** the low-level `validate_signature`
raises `TypeError` on a `None` signature, but the only production caller
(`validate_daraja_callback`) coerces `None`→`""` first, so the reject-path is safe.
Per Rules #5/#45/#46 this was documented via a test rather than changing security
code speculatively.

### Webhook source-gate hardening in this pass (additive + one docstring fix)

**Finding (Rule #47 — documentation described a control that does not exist):** the
docstring for `security_helpers.is_allowed_source` advertised a fail-open development
override, `PESAGUARD_ALLOW_UNRESTRICTED_WEBHOOK_SOURCE=1`, that **no code reads**. The
gate itself correctly **fails closed** (rejects when neither `DARAJA_SHARED_SECRET` nor
`DARAJA_ALLOWED_IPS` is configured) and uses constant-time `hmac.compare_digest`. The
misleading docstring matters because CI/staging workflow files *set* that env var
believing it relaxes validation, and `PRODUCTION_CERTIFICATION.md` references it — so an
operator (or a future change) could assume a fail-open switch exists. Per Rule #65, a
future "implementation" of the documented override would be a fail-open backdoor on the
webhook ingestion boundary (Rules #19/#55).

**Fix (docstring only — no security-code change):** rewrote the docstring to describe the
real, secure posture: the gate always fails closed, there is no override, and unconfigured
deployments must set the shared secret or IP allowlist. No behavioral change to the gate
(Rules #5/#46 — I did not speculatively touch the security-critical comparison).

**Regression guard (Rule #37/#38):** added `tests/test_webhook_source_gate.py` (4 tests):
(1) unconfigured → denied; (2) valid shared-secret header → allowed, wrong/absent secret →
denied; (3) IP allowlist exact + CIDR match vs non-listed IP; (4) the critical
`test_documented_unrestricted_override_is_not_implemented` sets
`PESAGUARD_ALLOW_UNRESTRICTED_WEBHOOK_SOURCE=1` and asserts the gate **still denies** — so
if anyone later implements the documented fail-open switch, this test fails loudly.

**Guard proven real (Rule #38):** temporarily injecting the fail-open override exactly as
the old docstring described made test (4) fail (`assert True is False`); reverting restored
green. No behavioral change shipped.

**Full M-Pesa suite re-run (this pass): 53 passed, 0 failed** (49 baseline + 4 new guard
tests) — no regressions.


### Self-review (integration phase)

- Integrates with existing architecture: yes — tests import existing modules only.
- Preserves tenant isolation: yes — credential loading is per-tenant; no cross-tenant path added.
- Preserves data integrity: yes — callback acceptance still gated on signature match.
- Race conditions: none introduced (pure HMAC checks).
- Security boundary: strengthened by pinning the verify-only boundary; no origination added.
- Duplicate functionality: no — additive tests; no payment origination implemented.
- Unnecessary network calls: none — all in-memory; OAuth tested with a dummy session.
- Scalability bottleneck: none introduced.
- Observability: unchanged (existing auth-failure security events confirmed).
- Tests: added (17), all passing; existing integration suites still green.
- Rollback/recovery: N/A for additive tests; production behavior untouched.

**Services 35 (STK Push origination), 37 (B2C origination), 38 (B2B), 39
(Transaction Query) remain intentionally unimplemented** — they are money-movement
/ origination capabilities outside the documented product scope and would require a
separately scoped, human-approved effort (Rule #61).

## Reconciliation services (43–50)

The financial core (Rules #26/#27: deterministic, explainable matching). All
services are implemented and covered. Verified this phase by running
reconciliation + determinism + utils + state + batch + phase3 + anomaly_rules +
anomaly_scoring → **41 passed, 0 failed (exit 0)**, and settlement (10) — all
green, including the phase-3 golden-dataset 100%-correct certification.

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 43 | Reconciliation Service | `reconciliation_engine.py` `ReconciliationEngine.reconcile` / `reconcile_batch` (`phase3.3`) | TESTED | Golden dataset; deterministic batch control totals, ambiguity review, and exact grouped matching in `test_reconciliation_financial_processing.py` |
| 44 | Reconciliation Job Service | `reconciliation_job.py` | TESTED | `test_reconciliation_batch.py` |
| 45 | Transaction Matching Service | `reconciliation_engine.py` matching rules + `reconciliation_scoring.score_match` | TESTED | Equal top scores are review outcomes; exact-only many-to-one and one-to-many groups in `test_reconciliation_determinism.py` and `test_reconciliation_financial_processing.py` |
| 46 | Exception Service | engine `EXCEPTION` outcomes + `discrepancy_dao.py` | TESTED | `test_phase3_reconciliation.py` (invalid input, sub-cent) |
| 47 | Unmatched Transaction Service | engine `UNMATCHED`/`MISMATCH` outcomes and batch `unmatched_ledger_records` | TESTED | `test_reconciliation_determinism.py`, `test_phase3_reconciliation.py`, `test_reconciliation_financial_processing.py` |
| 48 | Reconciliation Rules Service | engine matching-rule tags + `anomaly_rules.py` | TESTED | `test_anomaly_rules.py`, `test_phase3_reconciliation.py` |
| 49 | Reconciliation Result Service | engine `_result` evidence + `reconciliation_scoring` | TESTED | `test_anomaly_scoring.py`, `test_phase3_reconciliation.py` |
| 50 | Reconciliation Audit Service | `record_ground_truth`, `ReconciliationGroundTruth`, `action_audit.py`, `ReconciliationMatch` | TESTED | Versioned evidence in the atomic audit; append-only match decisions in `test_reconciliation_database_guards.py` |

### Candidate selection, group cardinality, and durable decisions (services 43–50)

Equal-scoring top candidates are now classified as `EXCEPTION` by the versioned
engine and `needs_review` by the legacy compatibility evaluator. Candidate IDs
remain in stable order for evidence, but no ledger row is selected. A unique
highest score continues to select the best candidate.

Cardinality policy is exact-only for automatic matching: one payment may match
multiple distinct ledger rows, or multiple payments may match one ledger row,
only when their same-reference, same-currency, in-window aggregate is exact.
An under-total many-to-one group is `PARTIAL`; an over-total group is `MISMATCH`.
Non-exact groups do not consume ledger rows. Duplicate transaction IDs are
`DUPLICATE`; duplicate normalized ledger identifiers reject the batch during
input validation; absent candidates are `UNMATCHED`. Refunds and reversals must reference the original transaction and
are reconciled as separate immutable events; they do not mutate the original
financial record or initiate a money movement.

Every persisted outcome requires a recognized phase-3 status and evidence
containing matching rules, match score/time, engine version, and rule version.
The same database transaction stores the result, the full evidence in the
append-only audit, the `ReconciliationMatch`, and the outbox. Completed outcomes
are replay-safe duplicates; direct database updates/deletes of match decisions
and illegal lifecycle edges are rejected by triggers. No authorized correction
workflow currently exists, so established outcomes cannot be replaced.

**Verification (2026-10-14):** reconciliation, grouped processing, batch
delivery, dead-letter, audit, lifecycle, and related database-constraint suites
→ **124 passed, 11 warnings**. Alembic resolves to one head,
`20261014_quarantine_remediation_history`; the new lifecycle/match guards are
its predecessor. The PostgreSQL migration and full-chain PostgreSQL upgrade
remain deployment checks.

### Self-review (reconciliation phase)

- Integrates with existing architecture: matching and persistence remain in the
  engine/job; lifecycle checks follow the existing transition maps.
- Preserves tenant isolation: reconciliation operates on caller-supplied records;
  no tenant boundary changed.
- Preserves data integrity: ambiguous candidates cannot be silently attached;
  non-exact payment groups do not consume ledger rows.
- Race conditions: optimistic transaction versions, scoped uniqueness, row locks,
  and atomic persistence prevent duplicate outcomes; concurrent SQLite tests
  verify one durable result.
- Auditability: matching rules, outcome, engine/rule version, score, timestamp,
  and full evidence are written atomically.
- Database boundary: migration triggers enforce lifecycle edges and match
  decision immutability; Alembic reports one head.
- Corrections: no correction endpoint exists. Completed decisions remain
  immutable until an explicitly authorized correction workflow is designed.

### Durable Kafka disposition and offset handling (services 44/47)

The reconciliation consumer uses at-least-once delivery with explicit offset
commits. A Kafka offset is a per-partition broker position; committing message
offset `n` stores `n+1`, the next position to consume. An application result or
counter is not an acknowledgement. The consumer commits only after either the
reconciliation transaction (idempotency state, audit record, and outbox) or a
tenant-scoped dead-letter record is durable.

Processing failure is quarantined before commit. The dead letter retains the
payload (raw malformed bytes are base64 encoded) and topic/partition/offset
coordinates. If reconciliation and dead-letter storage both fail, or the
explicit offset commit fails, the affected partition is paused and sought back
to the failed offset for timed retry; later messages in that partition are not
processed past it. Other partitions remain eligible to progress. Failure to
pause/seek or commit is surfaced rather than silently acknowledged. A crash
after durable database commit but before confirmed offset commit may redeliver:
the reconciliation ledger makes completed transactions a no-op, while the
dead-letter store deduplicates quarantine.

**Verification (2026-10-13):**
- `tests/test_reconciliation_dead_letter.py` covers dead-letter database
  outage/retry, partition pause and recovery, malformed payload quarantine,
  explicit per-message commit, and durable DLQ redelivery after commit
  uncertainty.
- SQLite-backed tests verify that successful redelivery leaves one processed
  transaction, audit entry, outbox entry, and fraud-risk assessment; durable DLQ
  redelivery leaves one auditable record with its Kafka coordinates.
- Exact command from `pesaguard_backend_pipeline/`:
  `python -m pytest -q --tb=short tests/test_reconciliation_utils.py tests/test_reconciliation_state.py tests/test_reconciliation_determinism.py tests/test_reconciliation_dead_letter.py tests/test_reconciliation_batch.py tests/test_reconciliation_audit_immutability.py tests/test_transaction_outbox.py`
  **46 passed** (one unrelated `datetime.utcnow()` deprecation warning). This is
  automated test evidence, not production verification; live broker
  pause/rebalance behavior and PostgreSQL outage recovery still require
  deployment-level validation.

### Financial matching and control totals (services 43–47)

`ReconciliationEngine` `phase3.3` now exposes deterministic bounded batch
reconciliation in addition to the worker's per-event path. It normalizes
identifiers, validates currency and timestamps, supports exact one-payment to
many-ledger-row matches, reports partial/overpaid discrepancies, detects
duplicate and unmatched rows, and returns signed per-currency control totals.
Duplicate ledger row IDs and batches containing multiple tenants are rejected.
The production worker fetches shared-reference candidates and persists grouped
ledger-row evidence in `ReconciliationMatch`.

Grouped auto-matching is intentionally conservative: same normalized reference
and currency, unique row IDs, all timestamps inside the configured window, and
an exact Decimal total are required. Partial or overpaid groups remain review
outcomes. Postgres deployments must configure separate `internal_ref` and
`reference` mappings to find split rows; see
`docs/product/RECONCILIATION_WORKFLOW.md` for the connector contract, batch
limits, rollback behavior, benchmark evidence, and deployment-verification gaps.

## Settlement services (51–55)

**Important scope nuance (Rule #47):** `settlement.py` implements an **internal
settlement calculation / status / reconciliation / reporting domain**. It
calculates `Decimal` totals from caller-supplied lines and compares them to a
caller-supplied expected net. It does not currently load/verify the lines against
authoritative source transactions or import/verify provider statements. A zero
variance is therefore only agreement with that supplied expected net, not
provider confirmation. It does **NOT** move money, call Daraja, initiate
payouts/B2C, or execute fund transfers. `SettlementService` remains service-layer
only: no HTTP route or worker wires it, so it is not customer-accessible.

Settlement input amounts and fees must be finite, non-negative, in-range, and
within currency minor-unit precision; fees cannot exceed line amounts. Duplicate
transaction IDs in one calculation are rejected, aggregation overflow is
rejected, and omitted lines are removed from the current line snapshot on
recalculation while prior input/totals remain in append-only audit snapshots.
Signed adjustment, chargeback, refund, and reversal entries are unsupported and
rejected for investigation. Recalculation cannot overwrite a reconciled/settled
outcome. ORM version columns reject stale writes. The integrity migration adds
database-enforced status edges and status-transition audit insertion, plus
update/delete protection for settlement audit history. `settled` means only that
the internal record was finalized.

Focused verification uses `tests/test_settlement.py` and
`tests/test_settlement_integrity_migration.py` for exact money math, minor units,
duplicate/rejected lines, aggregation overflow, idempotency, expected-net
variance, tenant isolation, optimistic locking, transaction rollback, immutable
calculation evidence, database status guards, and append-only audit behavior.
The final focused run across those suites and
`tests/test_currency_minor_units_migration.py` completed **31 passed, 0 failed**
on 2026-10-14. Alembic reports one head,
`20261014_settlement_integrity`; the SQLite migration tests run the revision in
isolation. PostgreSQL migration/concurrency behavior remains unverified. The
migration refuses downgrade after audit history exists to prevent silently
discarding it. No settlement HTTP/worker integration exists because the
capability is intentionally not exposed.

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 51 | Settlement Service | `settlement.py` `SettlementService` (facade) | TESTED, NOT EXPOSED | `test_settlement.py`; no customer-facing API or worker |
| 52 | Settlement Calculation Service | `SettlementCalculationService` (currency-precision validation; derived half-up totals; idempotent per `(tenant_id, settlement_key, currency)`) | TESTED | `test_settlement.py`, `test_currency_minor_units_migration.py` |
| 53 | Settlement Status Service | `SettlementStatusService` (state machine + database lifecycle guard) | TESTED | illegal/terminal transitions rejected, logged and counted; migration writes status audit records |
| 54 | Settlement Reconciliation Service | `SettlementReconciliationService` (variance = caller-supplied expected net − calculated net; non-zero → `disputed`) | TESTED | Does not import or verify a provider statement |
| 55 | Settlement Reporting Service | `SettlementReportingService` (read-only, tenant-scoped, Decimals as strings) | TESTED | tenant-scoped listing |

### Documentation consistency and observability

Per Rule #47, the product scope and the implemented internal settlement domain
must describe the same boundary:

1. **Product-scope conflict — RESOLVED.** `docs/product/PRODUCT_SCOPE.md` now
   records the internal calculation, variance reconciliation, and read-only
   reporting domain while retaining the explicit prohibition on provider
   confirmation, payouts, and fund movement. Reporting remains service-layer
   only; no HTTP route or worker was added.

2. **Settlement integrity gaps — addressed locally.** Illegal transitions emit a
   warning and increment the shared security-event metric before rejection.
   Optimistic locking prevents stale ORM updates. The integrity migration writes
   successful status changes as audit records from a database trigger, rejects
   invalid status edges from alternate SQL paths, and makes audit history
   append-only. PostgreSQL deployment behavior remains unverified.

### Self-review (settlement phase)

- Integrates with existing architecture: yes — services use existing models/session.
- Preserves tenant isolation: yes — every query/mutation is tenant-scoped; DB check
  constraints enforce non-empty tenant_id (verified by `test_settlement.py`).
- Preserves data integrity: yes — `Decimal`/`NUMERIC(19,3)`, currency-specific
  precision, half-up rounding for derived values only, duplicate rejection,
  aggregation range checks, immutable calculation snapshots, and DB status guards.
- Race conditions: idempotent insert per `(tenant_id, settlement_key, currency)`;
  optimistic version checks reject stale ORM updates. PostgreSQL concurrent-write
  behavior needs integration validation.
- Security boundary: unchanged; no money movement added. Illegal transitions are
  rejected, logged, and counted (Rule #8/#26/#40).
- Duplicate functionality: no — reuses existing models.
- Unnecessary network calls: none (pure DB).
- Scalability bottleneck: none introduced; tenant/period indexes exist.
- Observability: structured warning plus security-event metric on illegal
  transitions. A rejected-transition audit row shares the caller-owned database
  transaction and is rolled back if the caller rolls back; warning/metric are the
  independent rejection signals.
- Source/provider evidence: authoritative source-transaction and provider-statement
  imports are not implemented. Caller-provided lines and expected totals are not
  independently verified; provider transfer/settlement execution is unsupported.
- Rollback/recovery: calculations and their audit evidence are written in the
  caller-owned transaction and roll back together. PostgreSQL trigger and
  concurrency behavior remains a deployment verification requirement.

## Risk & fraud services (56–63)

Rule #28 is the governing constraint: fraud detection must distinguish
anomaly / suspicion / high-risk / confirmed fraud and must **never** auto-declare
fraud from a score alone. The engine complies — its highest action is `escalate`
(human review); there is no `confirm_fraud` action.

Verified by running existing suites: fraud-risk-engine / anomaly-rules /
anomaly-scoring / escalation-actions / alert-delivery-routing / alerting-monitoring
— **32 passed** (incl. 5 new boundary tests).

2026-10-13 production-hardening pass added (all passing; evidence in the
hardening note below): `test_fraud_events.py` (8), anomaly Decimal-threshold
regression (+1), alert tenant-scoping/audit-log (+2), fraud-route durable-event
(+1), `test_investigation_tenant_isolation.py` (11).

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 56 | Risk Service | `fraud_risk_engine.assess_transaction` + `FraudRiskAssessment` model | TESTED | `test_fraud_risk_engine.py`, `test_fraud_boundaries.py` |
| 57 | Anomaly Detection Service | `anomaly_rules.check_for_anomalies` / `score_transaction_anomaly` | TESTED | `test_anomaly_rules.py`, `test_fraud_boundaries.py`, `test_monetary_contract.py` |
| 58 | Fraud Detection Service | `fraud_risk_engine` layered rules + `fraud_events.py` publication | TESTED | `test_fraud_risk_engine.py` (Rule #28 boundaries) |
| 59 | Risk Scoring Service | `fraud_risk_engine` features/score; `algorithms.py` registry adapter; `reconciliation_scoring.score_match` | TESTED | `test_anomaly_scoring.py`, `test_fraud_risk_engine.py`, `test_algorithm_registry_real.py`, `test_monetary_contract.py` |
| 60 | Risk Rules Service | `fraud_risk_engine.evaluate_rules` (velocity/amount/duplicate/time/rapid) | TESTED | explainable `rules_triggered` |
| 61 | Risk Event Service | `fraud_events.py` (`publish_fraud_analysis`, `publish_fraud_review_decision`, `stage_fraud_analysis_event`); `/fraud/analyse` stages the envelope into `transaction_outbox` in the same commit as the assessment | TESTED | new `test_fraud_events.py` (8) + route-level durable-event test; replay stages zero extra rows |
| 62 | Investigation Service | `escalation_engine.py` (tenant-scoped `update_rule`/`delete_rule`, config validation), `on_call_service.py` (tenant-scoped `update_rotation`/`delete_rotation`, naive/aware-safe), `discrepancy_dao.py` | TESTED | new `test_investigation_tenant_isolation.py` (11: service boundary + live-route 404/400) + `test_escalation_actions.py` |
| 63 | Alert Service | `alerting_service.py` (tenant-scoped dedup, tenant-scoped `needs_review` delivery log), `notifier.py`, `alert_template_loader.py` | TESTED | `test_alert_delivery_routing.py` (+2 tenant-scoping/audit-log tests), `test_alerting_monitoring.py` |

### Rule #28 boundary pinned + monetary defense-in-depth gap closed

`tests/test_fraud_boundaries.py` pins:
- No action ever confirms fraud (highest = `escalate`); risk levels are the four
  documented tiers; a pathological amount yields a bounded, non-NaN score.
- The primary ingestion normalizer rejects NaN/Infinity, so non-finite amounts never
  reach anomaly scoring through normal ingestion.
- The secondary `anomaly_rules` scoring entry point independently classifies
  NaN/infinities, zero, negative, malformed, and unsupported-currency amounts as
  invalid. Its amount-threshold comparisons stay in `Decimal` arithmetic.

The algorithm registry risk-scoring adapter validates required transaction inputs,
rejects malformed history/baseline data, fails closed when its engine is unavailable,
and preserves the engine's human-review actions for high-risk results. Regression
coverage is in `tests/test_algorithm_registry_real.py`; the production fraud route
continues to use its tenant-scoped history query and persisted risk assessment
(`test_transaction_routes.py::test_fraud_analysis_persists_and_replays_idempotently`).
Other catalog entries in the registry's fraud and ledger categories return
`needs_review` until they have their own evaluator; they do not reuse unrelated
category-level checks as a success signal.

### Algorithm registry hardening verification (2026-10-11)

- Focused registry, fraud engine/boundary, reconciliation, Daraja signature, and
  transaction-route run: **78 passed, 1 failed**. The failure is
  `test_versioned_auth_aliases_are_registered` (`/api/v1/auth/login` returned 503
  rather than the expected 401); auth routing/rate-limiter files are outside this
  change. The fraud-analysis persistence/idempotency integration test passed.
- The focused registry file passed **28 tests**. Coverage measured with
  `coverage.py` started before test collection (the test imports `algorithms.py`
  through `importlib`): **70/83 changed executable statements covered (84.3%)**;
  whole-file coverage was 59%. `pytest.ini` does not configure a coverage
  threshold.
- Local micro-benchmark on Python 3.13.15: median **69.3 risk evaluations/sec**
  over five 500-evaluation trials, each with 100 history rows. This is a local
  synthetic baseline only, not a production-scale result or SLO.
- Production readiness is not certified by local tests: deployed database/Redis
  behavior, production telemetry, and production-like performance remain
  unverified. No schema, persistence, or money-movement behavior was added.

### Risk-event, investigation, and alert production hardening (2026-10-13)

Gaps closed in this pass, all through existing boundaries:

- **Service 61 wired into production.** `fraud_events` previously had no
  callers. `/fraud/analyse` (`transaction_routes.py`) now stages the versioned
  fraud envelope into `transaction_outbox` in the **same commit** as the
  persisted assessment and idempotency record, using event key
  `fraud-analysis:<Idempotency-Key>`. The existing `drain_transaction_outbox`
  worker delivers it with retries and dead-letter handling; idempotent replays
  stage zero extra rows. Evidence: new `tests/test_fraud_events.py` (direct
  publication topics/payloads, analyst-attributed review decision, staging
  atomicity + `uq_transaction_outbox_tenant_event` dedup) = **8 passed**, plus
  `test_transaction_routes.py::test_fraud_analysis_stages_a_durable_risk_event`.
- **Service 62 tenant isolation.** `EscalationEngine.update_rule`/`delete_rule`
  looked up rules by id only and `setattr`'d arbitrary request fields, so
  `PUT /escalation-rules/<rule_id>` (whose resource-permission decorator
  short-circuits for broad scope) could modify and reassign another tenant's
  rule. Lookup is now tenant-scoped (misses uniformly return `rule_not_found`,
  no existence oracle), updates are restricted to an allowlist (identity
  columns immutable), and unknown operators/actions/fields raise `ValueError`
  mapped to **400** at both route branches; cross-tenant misses map to **404**
  and successful updates record an action-audit entry. Same treatment for
  `OnCallService.update_rotation`/`delete_rotation`. Also fixed a latent
  naive-vs-aware crash in `update_rotation`'s `is_active` recomputation
  (SQLite returns naive datetimes). Evidence: new
  `tests/test_investigation_tenant_isolation.py` = **11 passed** (7
  service-boundary + 4 live-route tests confirmed failing pre-fix).
- **Service 63 tenant scoping + audit persistence.** Alert deduplication is now
  keyed `tenant_id:alert_id` so one tenant can never suppress another's alert,
  and the delivery-log row uses the DAO's tenant-scoped id convention. The log
  row previously wrote `status="alerted"`, which violates
  `ck_discrepancy_status`, so delivery audit rows were **never persisted**
  (integrity error swallowed); it now writes `status="needs_review"` with a
  constraint-safe severity. `TestEscalationRules`/`TestOnCallRotations` route
  tests and all existing escalation/alert suites remain green.

### Pre-existing failures noted (NOT caused by this pass — Rule #38)

`tests/test_device_risk_auth.py` has **3 failing tests** (`503 SERVICE UNAVAILABLE`
on login). Verified these are **pre-existing and unrelated**: the file imports only
`app_4_advanced_features` (5809 lines of divergent-branch changes not authored here)
and does **not** import `reconciliation_engine`, `settlement`, or anything changed in
this work. The 503 indicates an unavailable auth-runtime dependency (e.g. Redis) in
this environment. No action taken — flagged for human investigation.

### Self-review (risk & fraud phase)

- Integrates with existing architecture: yes — tests import existing modules only.
- Preserves tenant isolation: risk decisions persist under `(tenant_id, transaction_id)`.
- Preserves data integrity: scores bounded [0,1]; NaN rejected at ingestion; bounded here.
- Race conditions: none introduced (pure functions).
- Security boundary: unchanged; Rule #28 respected (no auto-fraud).
- Duplicate functionality: no — reuses existing engines.
- Unnecessary network calls: none (in-memory; event publishing injects a publisher).
- Scalability bottleneck: none introduced.
- Observability: fraud events carry correlation IDs; escalation/alerting instrumented.
- Tests: added (5), all passing; risk/fraud suites green (32).
- Rollback/recovery: N/A — no production code changed in this pass.

### Currency-aware monetary contract verification (2026-10-13)

The authoritative amount contract is `normalization.py`; adapters and scoring
entry points share it rather than maintaining KES-only or float-based amount
parsers. Supported scales are KES/TZS/USD/EUR/GBP (2), UGX (0), and BHD (3).
Non-finite, non-positive, unsupported, out-of-range, and over-precision
transaction values reject before scoring/storage. Excess input precision is
rejected; `ROUND_HALF_UP` is limited to explicitly derived values. Provider
payloads remain available in raw form; comparisons, reconciliations, baselines,
settlement keys, and merchant metric buckets do not combine currencies and do
not perform FX conversion.

CSV/Excel purchase-order and ledger imports now normalize through the same
contract and retain the source row. Core data-engineering aggregates keyed by a
non-currency dimension use `(dimension, currency)` keys. Reconciliation
candidate lookup scopes amount matches by currency but still returns
reference-matched rows for explicit mismatch classification. Notification
thresholds compare only within their configured currency; incomparable
reconciliation amounts escalate to critical review.

Focused test command (run from the repository root):

```text
python -m pytest -q --tb=short pesaguard_backend_pipeline\tests\test_currency_minor_units_migration.py pesaguard_backend_pipeline\tests\test_monetary_contract.py pesaguard_backend_pipeline\tests\test_normalization.py pesaguard_backend_pipeline\tests\test_normalization_hardening.py pesaguard_backend_pipeline\tests\test_ingestion.py pesaguard_backend_pipeline\tests\test_ingestion_tenant_isolation.py pesaguard_backend_pipeline\tests\test_batch_ingestion.py pesaguard_backend_pipeline\tests\test_realtime_pipeline.py pesaguard_backend_pipeline\tests\test_realtime_worker.py pesaguard_backend_pipeline\tests\test_api_schema_validation.py pesaguard_backend_pipeline\tests\test_schema_validation.py pesaguard_backend_pipeline\tests\test_algorithm_registry_real.py pesaguard_backend_pipeline\tests\test_anomaly_rules.py pesaguard_backend_pipeline\tests\test_anomaly_scoring.py pesaguard_backend_pipeline\tests\test_fraud_boundaries.py pesaguard_backend_pipeline\tests\test_fraud_risk_engine.py pesaguard_backend_pipeline\tests\test_reconciliation.py pesaguard_backend_pipeline\tests\test_reconciliation_batch.py pesaguard_backend_pipeline\tests\test_reconciliation_determinism.py pesaguard_backend_pipeline\tests\test_reconciliation_financial_processing.py pesaguard_backend_pipeline\tests\test_reconciliation_state.py pesaguard_backend_pipeline\tests\test_reconciliation_utils.py pesaguard_backend_pipeline\tests\test_aggregation_service.py pesaguard_backend_pipeline\tests\test_settlement.py pesaguard_backend_pipeline\tests\test_purchase_order_matching.py pesaguard_backend_pipeline\tests\test_core_data_engineering.py pesaguard_backend_pipeline\tests\test_communications_intelligence.py pesaguard_backend_pipeline\tests\test_phase1_integrity.py pesaguard_backend_pipeline\tests\test_phase1_full_integrity.py pesaguard_backend_pipeline\tests\test_transaction_outbox.py pesaguard_backend_pipeline\tests\test_reconciliation_audit_immutability.py pesaguard_backend_pipeline\tests\test_daraja_validation.py pesaguard_backend_pipeline\tests\test_connectors.py pesaguard_backend_pipeline\tests\test_api_idempotency_contract.py
```

The focused run completed with **319 passed, 11 warnings in 128.87s**. A diff-based
coverage audit measured **984/1291 changed executable statements (76.2%)**,
below the implementation workflow's 80% target; the coverage gate remains
incomplete, particularly across legacy event-store, reconciliation-job,
validator, and normalization branches. The currency
migration and settlement rollback/currency tests were then re-run separately:
**16 passed in 27.11s**, including the KES-only safe downgrade path. When
`test_transaction_routes.py` was added separately, its unrelated
`test_versioned_auth_aliases_are_registered` failed (expected HTTP 401, got
503). `test_fraud_events.py` could not be collected because its source has an
`IndentationError` at line 165. A full SQLite Alembic upgrade is blocked by the
earlier `20260909_scope_transaction_identity` revision's PostgreSQL-only `md5()`
function; the currency revision itself is exercised against a legacy SQLite
schema. PostgreSQL migration execution, production data backfill, and rollback
behavior remain deployment verification items; local test success is not
production certification.

## Customer / account services (64–68)

**Scope finding (Rule #3/#47):** these are largely **not implemented** in the
reconciliation backend, and the missing pieces would require a customer/account/
balance **ledger** that is explicitly out of scope. `PRODUCT_SCOPE.md` states
PesaGuard "does not... maintain a settlement ledger," and the developer-platform
backend marks `customers:read`/`customers:write` API scopes as
"This API capability is not yet implemented" (Java migration `V63`). The dashboard
frontend has `customers.api.ts`/`accounts.api.ts` client stubs pointing at an
unimplemented backend.

Evidence: the complete DB table list in `models.py` contains **no** `customers`,
financial `accounts`, `balances`, `customer_transactions`, or
`account_relationships` tables. The only account-like table is `user_accounts`
(platform login identity, service 4) — not a business customer.

The algorithm registry's debit-credit check is only an arithmetic consistency
validator for a supplied entry. It does not own, persist, or calculate customer
balances and does not add a financial ledger service. Other ledger catalog entries
remain not implemented until they have dedicated validators.

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 64 | Customer Service | Not implemented as an entity; `enrichment_service` has a pluggable `customer_lookup` seam (defaults to `{}`) | NOT IMPLEMENTED (out of scope) | No `customers` model/table; would require a customer ledger |
| 65 | Account Service (financial) | Not implemented; only `user_accounts` (platform login) and Java `ServiceAccount` (developer platform) exist | NOT IMPLEMENTED (out of scope) | No financial account entity |
| 66 | Customer Transaction Service | Partially: `transactions` table + `/transactions/search` + history are the core entity | PARTIAL (derivable) | A customer-scoped transaction view could be derived from existing search; no dedicated service |
| 67 | Account Balance Service | Not implemented | NOT IMPLEMENTED (out of scope) | Requires a balance/ledger — excluded by `PRODUCT_SCOPE.md` |
| 68 | Account Relationship Service | Not implemented | NOT IMPLEMENTED (out of scope) | No relationship model |

### In-scope analog that IS implemented and tested

- **Merchant analytics** (`aggregation_service.py` + `MerchantMetric` model) — the
  closest existing analog to a customer/account rollup: per-merchant transaction
  counts, volume, averages, failure/reconciliation rates. **TESTED** —
  `test_aggregation_service.py`, `test_enrichment_service.py` (**3 passed**).
- **Enrichment** (`enrichment_service.py`) — attaches merchant/customer context to a
  canonical transaction via injectable lookups, without owning a customer store.

### Recommendation (human decision required — Rule #61)

Services 64, 65, 67, 68 are **balance/ledger-tracking capabilities outside the
documented product scope**. Implementing them is a **separately scoped effort** that
would require: a customer/account data model, accounting invariants (double-entry or
equivalent), a balance ledger with tenant isolation, and explicit human approval —
exactly as `PRODUCT_SCOPE.md` and Rule #61 require. I did **not** build them.

A minimal, in-scope increment that could be approved later: a **read-only,
tenant-scoped "customer transactions" view (service 66)** derived from the existing
`transactions` search/history — no new ledger, no balances. Flagged for decision.

## Async / background processing services (78–83)

Services 78–81 (durable scheduler, RQ queue, phase manager, distributed lock) are
implemented and covered by dedicated suites. The two helper-level gaps below were
genuinely untested: the retry helper used by the Daraja OAuth client, and the RQ
job-failure hook plus tenant-scoped dead-letter replay.

Verified: `test_scheduler.py` + `test_phase_manager.py` + `test_distributed_lock.py`
= **22 passed**; `test_infra_locking_scheduling.py` = **16 passed**; new
`test_async_job_reliability.py` = **12 passed**.

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 78 | Job Service | `background_tasks.py` enqueue paths (transaction events, reconciliation, batch drain) + RQ job functions | TESTED | RQ retry (max=3) + `on_failure` → DLQ hook |
| 79 | Worker Service | `realtime_worker.py`, RQ workers, scheduler `run_due` per replica | TESTED | `test_realtime_worker.py`, `test_scheduler.py` (busy-lock skip) |
| 80 | Queue Service | Redis/RQ queues (`transaction_events`), bounded claim/drain (`claim_outbox_batch`, `drain_transaction_outbox`) | TESTED | `DEFAULT_BATCH_SIZE=50` per scheduler tick prevents runaway |
| 81 | Scheduler Service | `scheduler.py` `Scheduler`, `scheduler_worker.py`, `scheduled_tasks.py`: durable tenant-scoped jobs, DAG dependencies, bounded retries/timeouts, idempotent backfills, attempt history, authenticated operator/recovery API | PARTIALLY TESTED | Focused regression suite: 82 passed across scheduler, SQLite migration, worker/task, operator HTTP, DLQ, and authorization tests. Focused production-module coverage: `scheduler.py` 80%, `scheduler_worker.py` 96%, `scheduled_tasks.py` 100%. Disposable PostgreSQL 16 validation passed for the full Alembic upgrade, workflow downgrade/re-upgrade, scheduler grants and forced RLS, pooled-connection tenant-context isolation, and registry-role boundaries (`tests/test_postgres_scheduler_orchestration.py`: 2 passed). Target-deployment credential/RLS verification, alert receiver delivery, and performance evidence remain release gates; see `docs/operations/SCHEDULER.md` and `docs/runbooks/PIPELINE_ORCHESTRATION.md` |
| 82 | Retry Service | `utils/retries.retry_with_backoff` (exponential backoff + full/none jitter) used by `shared/daraja/oauth.py` | TESTED (gap closed) | New: 6 retry tests |
| 83 | Dead-Letter Service | `background_tasks.handle_job_failure` (RQ hook → `DeadLetter`) + `replay_dead_letter_job` (tenant-scoped republish) | TESTED (gap closed) | New: 6 DLQ tests; event-bus DLQ covered in phase4 |

### Gaps closed in this pass (additive only)

Added `tests/test_async_job_reliability.py` (**12 tests**):
- **Service 82:** first-success returns without sleeping; retry-then-success with
  deterministic `base * 2**attempt` schedule under `jitter="none"`; the original
  exception is re-raised after exactly `retries` retries; backoff capped at
  `max_backoff` with full jitter bounded in `[0, cap]`; `retries < 0` and unknown
  jitter strategies rejected (Rules #59/#20: validated, fail-clear startup config).
- **Service 83:** failed RQ jobs persist a `DeadLetter` row with `reason =
  background_job_failed`, error detail and tenant derived from the payload
  (defaulting to `"default"` when absent); replay of a `queued` entry publishes
  and marks `published`/`processed`; replay under the wrong tenant is a no-op (no
  publish, row untouched — Rule #11); `idle` entries are ignored; a publish failure
  marks `failed`, preserves the error, and re-raises so the caller observes it.

**12 passed.**

### Self-review (async/background phase)

- Integrates with existing architecture: yes — tests import existing helpers only.
- Preserves tenant isolation: yes — DLQ rows carry tenant; replay is tenant-scoped.
- Preserves data integrity: yes — failed jobs are durably recorded, never silently dropped.
- Race conditions: none introduced (test doubles; real paths use per-key locks).
- Security boundary: unchanged.
- Duplicate functionality: no — new tests target previously untested helpers.
- Unnecessary network calls: none — sleeps/publisher/Kafka providers mocked or injected.
- Scalability bottleneck: none introduced (bounded attempts/caps unchanged).
- Observability: existing structured logging + `last_error`/`failure_count`/
  `last_run_at` counters confirmed; no changes needed.
- Tests: added (12), all passing; scheduler/phase/lock suites still green (22).
- Rollback/recovery: N/A — no production code changed in this pass.

### Self-review (customer/account phase)

- Integrates with existing architecture: N/A — nothing built (out of scope).
- Preserves tenant isolation: existing merchant/enrichment services are tenant-scoped.
- Preserves data integrity: N/A.
- Race conditions: N/A.
- Security boundary: no new surface; nothing built.
- Duplicate functionality: avoided by NOT building out-of-scope ledger services.
- Unnecessary network calls: none.
- Scalability bottleneck: none introduced.
- Observability: existing merchant/enrichment paths unchanged.
- Tests: existing merchant/enrichment suites green (3).
- Rollback/recovery: N/A — no code changed in this pass.

## Webhooks & events services (69–77)

Baseline: the **event side was already well-tested** (13 tests in
`test_phase4_event_bus.py` + streaming alignment): versioned event contract, topic
routing, duplicate-processed-once, consumer crash retry → DLQ, replay preserving
event identity, broker-unavailable without ack, batch publish per-message results,
backpressure retaining dead letters. The **webhook side had a single test**
(delivery-history tenant scoping) — the asymmetry that this pass closed.

Verified: event suites **16 passed** (phase4 + streaming + original webhook test);
realtime pipeline/worker **2 passed**; new webhook security suite **17 passed**.

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 69 | Webhook Management Service | `webhook_manager.WebhookManager` register/get/update/rotate/revoke; globally-routable DNS policy | PARTIALLY TESTED | Registration, configuration bounds, secret lifecycle and cross-tenant HTTP access are locally tested; migration `20261017_webhook_runtime_grants` gives the non-bypass runtime role only config CRUD; disposable PostgreSQL 16 role/RLS integration passed; target-deployment grants and production egress/DNS controls remain unverified |
| 70 | Webhook Delivery Service | `_deliver_webhook` using pinned HTTPS, certificate/hostname verification, no proxy environment, no redirects, durable attempt rows | PARTIALLY TESTED | Mocked transport verifies target pinning, bounded response/timeout, crash-visible processing state, and stale-row recovery; disposable PostgreSQL 16 verifies forced delivery RLS, tenant/no-context read isolation, read/insert/update grants, and denied application DELETE; no real production endpoint/TLS or deployed recovery worker verified |
| 71 | Webhook Retry Service | 1–5 attempts with full-jitter exponential delays, 30-second total request timeout per attempt | PARTIALLY TESTED | Retry classes, terminal 4xx/TLS/redirect, max-attempts, jitter, crash recovery, lock contention and attempt-budget exhaustion are locally tested; DNS has a shared 3-second resolver deadline |
| 72 | Webhook Signature Service | HMAC-SHA256 over exact transmitted UTF-8 JSON bytes; timestamp and stable event ID headers | PARTIALLY TESTED | Exact bytes, invalid signature, stale timestamp, malformed/non-finite JSON, event identity, rotation and revocation covered; receiver-side replay persistence remains external |
| 73 | Event Service | `event_bus.py` versioned contract (`EVENT_TYPES`, `build_event`, schema/event versions) | TESTED | `test_phase4_event_bus.py` |
| 74 | Event Publisher | `producer.publish_versioned_event` + batch publish; broker-unavailable raises without ack | TESTED | per-message delivery results pinned |
| 75 | Event Consumer | `event_consumer.py` + `realtime_worker.py`: idempotent processing, bounded retry, backpressure | TESTED | duplicate-once + crash-retry pinned; `test_realtime_worker.py` |
| 76 | Dead-Letter Queue Service | `dead_letters` table + phase4 DLQ placement; never silently discarded (Rule #10/#31) | TESTED | poison message → DLQ; network failure → DLQ; DLQ retained on backpressure |
| 77 | Event Replay Service | phase4 replay preserves event identity after retry exhaustion | TESTED | replay-without-handler preserves dead letter |

### Webhook security and delivery implementation evidence

The follow-up implementation adds direct IP-pinned HTTPS transport (no second
DNS resolution at connect), rejects any non-global DNS result, disallows
proxy-environment routing, preserves TLS certificate and hostname checks, and
refuses every 3xx. The canonical JSON bytes are both signed and transmitted.
Attempt rows and attempt counters commit before outbound I/O; terminal outcome
metadata is committed before the result is returned. Stale attempts now have a
tenant-scoped recovery worker and exhausted ambiguous attempts become visible
terminal failures. Full-jitter exponential
retry is bounded to five attempts with a 30-second total request timeout.
Registration, listing, secret rotation/revocation, update, and delivery-history
surfaces avoid unintended secret disclosure and enforce tenant ownership.

`webhook_operations_worker.py` provides a one-shot recovery and health tick.
It enumerates active tenants only via the least-privilege tenant registry,
then scopes business-table processing by tenant. Configure it as a supervised
periodic job per deployment. This repository change does not provision the
production scheduler, Redis instance, registry role, external alert receiver,
or operator replay authorization process.

Tests added/extended in `test_webhook_management_security.py`,
`test_webhook_manager.py`, `test_webhook_operations_worker.py`, and
`test_advanced_features.py` cover mixed
public/private A/AAAA results, DNS rebinding between resolution and connect,
bounded A/AAAA DNS queries and timeout failure, proxy environment, TLS
verification configuration, response size, HTTP timeout,
redirect and retry classifications, durable pending state after delivery/retry
crashes, outcome-persistence failures, exact-body HMAC verification, invalid
signatures/timestamps/payloads, stable receiver idempotency identity, secret
rotation/revocation, cross-tenant route denial, stale delivery recovery, and
tenant-scoped worker invocation. Evidence is from local tests with mocked
outbound network only. Receiver-side idempotency is still required.

Final focused local validation: webhook security/manager tests **49 passed**;
advanced-feature webhook HTTP tests **4 passed**; least-privilege
authorization tests **16 passed**; recovery worker and targeted migration
tests **5 passed**. Focused coverage reported `webhook_manager.py` **82%**,
`webhook_operations_worker.py` **95%**, and `escalation_engine.py` **37%**
overall (the file includes unrelated, unexercised escalation features; the
changed webhook-health method's statements were exercised). Python
compilation, scoped `git diff --check`, `pip check`, CLI help, and
migration-head discovery passed. The targeted migration was exercised
against SQLite. The full migration chain was subsequently upgraded on a
disposable PostgreSQL 17.11 Docker database, downgraded one revision, and
upgraded again. The database reached
`20261014_webhook_delivery_recovery`; the delivery table, recovery and
tenant-scoped indexes, forced row-level security, and both tenant policies
were verified. The migration now bootstraps the delivery table when earlier
revisions had skipped it because it was absent. A full offline `alembic
upgrade head --sql` attempt remains blocked by a pre-existing migration that
calls `sa.inspect` on Alembic's offline `MockConnection`. The PostgreSQL
database was disposable and local; this does not verify a deployed database,
production DNS behavior, firewall/egress enforcement, production proxy
settings, real TLS certificates, receiver replay storage, or recovery in a
deployed worker. The post-fix combined webhook manager, security, worker, and
migration regression run passed **55 tests**.

See [WEBHOOK_DELIVERY.md](../operations/WEBHOOK_DELIVERY.md) for transport
policy, signature contract, retry bounds, and operational limitations. No
production egress, DNS, TLS endpoint, receiver replay store, or deployed retry
recovery evidence is claimed.

## Reporting & analytics services (84–89)

Verified: export sanitization/formulas = **8 passed**; scheduled reports = **1
passed** (tenant-scoped generation); aggregation + enrichment = **3 passed**;
dashboard API = **15 passed**; new boundary suite below = **6 passed**.

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 84 | Reporting Service | `operations.scheduled_reports.generate_report_for_tenant` + `Report` model + `customer_reports` listing | TESTED | `test_scheduled_reports.py` (tenant-scoped counts + persisted report) |
| 85 | Dashboard Metrics Service | `api/dashboard_app.py` discrepancy read/resolve, pagination, `MerchantMetric` reads | TESTED | `test_dashboard_api.py`, `test_dashboard_features.py` |
| 86 | Analytics Service | `customer_transactions` + `customer_transaction_detail` (transactions + matched internal record) | TESTED (gap closed) | New: `test_reporting_tenant_boundary.py` |
| 87 | Usage Analytics Service | `tenant_organization_service.get_usage_summary` (`TenantUsage`/`TenantLimit`) | TESTED | `test_tenant_organization_service.py` (usage summary case) |
| 88 | Report Generation Service | `generate_report_for_tenant` (persisted `Report` row with `content.summary`) | TESTED | status `ok` + content assertions |
| 89 | Export Service | `export_routes.py` CSV export + customer deadletter/report/audit/transaction listings | TESTED | formula sanitization, tenant scoping, scope enforcement |

### Gaps closed in this pass (additive only)

Added `tests/test_reporting_tenant_boundary.py` (**6 tests**), replicating the
established `app_2` + real-`AuthRBAC`-token pattern from `test_dashboard_api.py`
(no bypassed auth — Rule #12) and closing the two untested sensitive routes:
- `customer_transaction_detail` (full record incl. raw payload) and
  `customer_transactions` list: cross-tenant reads denied (401/403/404) with no
  tenant-b content in the body; own-tenant list contains no foreign rows.
- `customer_reports` listing: cross-tenant denied with no content leak.
- `customer_audit` listing: cross-tenant denied; own-tenant access by a `read-only`
  role (which lacks `read:settings`) denied by authorization.
- `/v1/export/csv` without `tenant_id` rejected (400/401); auth-required endpoints
  reject unauthenticated callers.

**6 passed.** Two test-authoring corrections made during development were themselves
evidence: AuthRBAC rejects tokens whose claims don't match a live DB account
(fail-closed), and invalid roles are rejected at token-creation time — both
consistent with the required fail-closed posture.

### Self-review (reporting & analytics phase)

- Integrates with existing architecture: yes — tests use the shipped `app_2`
  dashboard app + real AuthRBAC tokens; no new wiring.
- Preserves tenant isolation: yes — cross-tenant denial pinned on all
  export/report/analytics routes touched here.
- Preserves data integrity: yes — exports sanitize spreadsheet formulas; reports
  persist auditable rows.
- Race conditions: none introduced (read-only routes).
- Security boundary: strengthened — previously untested detail/audit routes now
  have cross-tenant + authorization denial pinned (Rules #11, #13, #16).
- Duplicate functionality: no — additive tests only.
- Unnecessary network calls: none — Flask test client, in-process SQLite.
- Scalability bottleneck: none introduced; export/queries carry limits (5000 CSV,
  200 listing rows).
- Observability: route failures log with tenant context; unchanged.
- Tests: added (6), all passing; existing export/report/analytics suites green.
- Rollback/recovery: N/A — no production code changed in this pass.


## Dashboard services (100–108)

Services 100–108 are the operational dashboard surfaces served by the canonical
`app_2` (`api/dashboard_app.py`) Flask app. The sensitive, shared boundary is
**tenant scoping on every read** plus the two unauthenticated surfaces
(`/health`, `/metrics`) that must not leak dependency internals or metrics.

Verified: new boundary suite `test_dashboard_services_boundary.py` = **19 passed**;
existing `test_dashboard_api.py` = **16 passed**; `test_dashboard_features.py` +
`test_public_status.py` = **7 passed** (no regression from the shared `app_2` reload).

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 100 | Dashboard Overview Service | `dashboard_app` `/discrepancies`, `/assignment-queue`, `/activity-feed` (read:discrepancies) | TESTED | tenant-scoped list + 401 unauth |
| 101 | Dashboard Transaction Service | `/api/v1/metrics/merchants` (`MerchantMetric`, read:analytics) | TESTED | scoped to `get_current_user().tenant_id`; invalid granularity → 400 |
| 102 | Dashboard Reconciliation Service | `/analytics/reconciliation-report`, `/analytics/sla-metrics` | TESTED | tenant-scoped aggregates |
| 103 | Dashboard Risk Service | `/analytics/operator-stats`, `/incidents/search` | TESTED | tenant-scoped risk/incident reads |
| 104 | Dashboard Integration Service | `/providers` + `/providers/<id>` (`ProviderManagementService`, `PaymentProvider`) | TESTED | list + detail scoped; cross-tenant provider → 404 |
| 105 | Dashboard Activity Service | `/activity-feed` (recent discrepancy timeline) | TESTED | tenant-scoped |
| 106 | Dashboard Usage Service | `tenant_org_routes.usage_summary` → `get_usage_summary` (`/api/v1/organizations/<org_id>/usage`, read:usage) | TESTED | scoped by authenticated tenant; 401 unauth |
| 107 | Dashboard Security Service | `/v1/settings` GET/POST (read/write:settings) | TESTED | cross-tenant body `tenant_id` → 403 `tenant_access_denied` |
| 108 | Dashboard Production/Health Service | `/health` (`sanitize_health_payload`), `/metrics` (Prometheus token or read:metrics) | TESTED | health strips dependency errors; metrics 401 unauth |

### Gap closed in this pass (additive only)

Added `tests/test_dashboard_services_boundary.py` (**19 tests**), replicating the
established `app_2` + real-`AuthRBAC`-token pattern (no bypassed auth — Rule #12).
The suite seeds tenant-b data across every dashboard domain (transaction,
discrepancy, merchant metric, payment provider) and pins, for a caller
authenticated as tenant-a:
- Every tenant-owned read returns **no tenant-b content** (Rule #11).
- Provider detail for a tenant-b provider returns **404**, not 200 (IDOR, Rule #13).
- `/v1/settings` rejects a cross-tenant `tenant_id` in the body with **403**.
- `/health` (anonymous) is **sanitized** — no DB/Kafka host:port or error strings
  leak (Rule #18); only per-check `status` is returned.
- `/metrics` (anonymous) is **401**; authenticated readers get metrics without
  tenant leakage.
- Merchant-metrics and usage payloads carry the **authenticated** tenant scope
  regardless of any id in the path.

**19 passed.** Two test-authoring corrections were themselves evidence of correct
fail-closed behavior: provider IDs are validated against `^provider_[0-9a-f]{12}$`
(400 before any DB lookup), and the usage route is mounted under `/api/v1`.

### Self-review (dashboard phase)

- Integrates with existing architecture: yes — tests drive the shipped `app_2`
  dashboard app + real AuthRBAC tokens; no new wiring, no production code changed.
- Preserves tenant isolation: yes — cross-tenant denial pinned across all nine
  dashboard read surfaces plus the settings write guard (Rules #11, #13).
- Preserves data integrity: yes — read-only surfaces; no writes mutated tenant-b.
- Race conditions: none introduced (read-only + one guarded settings write).
- Security boundary: strengthened — `/health` sanitization and `/metrics` gating
  are now pinned against anonymous callers (Rules #16, #18).
- Duplicate functionality: no — additive tests only.
- Unnecessary network calls: none — Flask test client, in-process SQLite; `/health`
  network probes are monkeypatched in the sanitization test.
- Scalability bottleneck: none introduced; existing routes carry limits.
- Observability: structured per-request logs unchanged.
- Tests: added (19), all passing; dashboard/health/public-status suites green.
- Rollback/recovery: N/A — no production code changed in this pass.


## Security & audit services (109–117)

Services 109–117 are implemented in `security_audit_services.py` and share one
security-critical contract: **tenant isolation is fail-closed and every security
decision denies unless a valid, tenant-scoped grant is present.**

`tests/test_security_audit_services.py` already pins the happy paths (masking,
same-tenant rotation/revocation, high-risk block). This pass adds
`tests/test_security_audit_isolation.py` (**27 tests**) closing the isolation and
fail-closed boundary gaps. Verified: existing suite **4 passed** + new suite
**27 passed** = **31 passed** together.

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 109 | Security Service | `SecurityService.record_event` + `sanitize_details` (append-only `AuditEvent`, masked secrets, `payload_hash`) | TESTED (gap closed) | requires tenant + event_type; masks secret/token/password/key/authorization/cookie |
| 110 | Audit Log Service | `AuditLogService.append` / `list_events` (tenant-scoped query) | TESTED (gap closed) | tenant-a read never returns tenant-b rows |
| 111 | Security Event Service | `SecurityEventService.record` (delegates to `SecurityService`) | TESTED (gap closed) | masks `authorization`; persists tenant-scoped |
| 112 | Access Control Service | `AccessControlService.authorize` / `ensure_tenant_scope` (`SecurityDecision`) | TESTED (gap closed) | missing_actor / cross_tenant / role_denied / permission_denied / allowed / tenant_scope_mismatch |
| 113 | Secret Management Service | `SecretManagementService` create/get/rotate/revoke/list (versioned, masked) | TESTED (gap closed) | cross-tenant read isolated; value hidden by default; revoke invalidates; rotate replaces |
| 114 | Credential Rotation Service | `CredentialRotationService.rotate` (`ServiceCredentialRecord`, old→revoked, new→active) | TESTED (gap closed) | per-tenant: rotating tenant-a does not revoke tenant-b's active credential |
| 115 | Token Revocation Service | `TokenRevocationService.revoke_session` / `revoke_user` / `is_revoked` (`UserSession`) | TESTED (gap closed) | wrong-tenant revoke is a no-op; `is_revoked` honours tenant filter; `revoke_user` counts only that tenant |
| 116 | Threat Detection Service | `ThreatDetectionService.evaluate` (bounded risk score → allow/monitor/block) | TESTED (gap closed) | low-risk allow path; missing tenant fail-closed |
| 117 | IP / Device Security Service | `IPDeviceSecurityService` evaluate_ip / validate_device (deny/allowlist, known devices) | TESTED (gap closed) | allowlist enforced once set; missing-IP denied; device trust is tenant+user scoped |

### Gap closed in this pass (additive only)

Added `tests/test_security_audit_isolation.py` (**27 tests**), driving the real
service classes against the conftest-managed test database. The suite pins the
boundaries the happy-path suite left open (Rules #11, #13, #55, #57):
- **Cross-tenant isolation** on secrets (same name, different tenant → isolated
  values), credentials (rotating tenant-a leaves tenant-b's active credential
  intact), sessions (wrong-tenant `revoke_session` is a no-op; `is_revoked`
  honours the tenant filter; `revoke_user` counts only that tenant), audit reads
  (tenant-a listing never returns tenant-b rows), and devices (trust keyed by
  tenant+user).
- **Fail-closed authorization** edges: missing actor, role denied, permission
  denied, and tenant-scope mismatch all deny with explicit reasons.
- **Secret lifecycle**: value hidden by default, revoke invalidates reads and is
  idempotent, rotate replaces the previous value, rotate-missing raises.
- **Threat detection** low-risk allow path and missing-tenant rejection.
- **IP allowlist** enforcement once configured, and missing-IP denial.

**27 passed.** Two test-authoring corrections were themselves evidence of the
contract: the `UserSession` seed must actually `commit()` for the fail-closed
`revoke_session` to observe it, and secret/credential isolation is enforced by
explicit `tenant_id` filters in every method (verified, not assumed — Rule #62).

### Self-review (security & audit phase)

- Integrates with existing architecture: yes — tests drive the existing
  `security_audit_services` classes and the conftest test database; no new wiring,
  no production code changed.
- Preserves tenant isolation: yes — cross-tenant denial pinned across secrets,
  credentials, sessions, audit reads, and devices (Rules #11, #13).
- Preserves data integrity: yes — append-only audit; rotation preserves the prior
  credential as `revoked` (never deleted).
- Race conditions: none introduced (read/append-only + single-row revoke).
- Security boundary: strengthened — previously untested isolation and fail-closed
  edges are now pinned (Rules #16, #55, #57).
- Duplicate functionality: no — additive tests only.
- Unnecessary network calls: none — in-process SQLAlchemy sessions.
- Scalability bottleneck: none introduced.
- Observability: service-level `record_security_event` hook unchanged.
- Tests: added (27), all passing; existing security/audit suite green (4).
- Rollback/recovery: N/A — no production code changed in this pass.


## Platform infrastructure services (124–138)

Services 124–138 already carry broad coverage from earlier suites: config
validation, request-context bounds, cache isolation/bounds/JSON-only, limiter and
lock validation, feature-flag kill switch, version sanitization
(`test_platform_infrastructure_services.py`, 9); liveness-without-probes and
readiness/health sanitization with status mapping (`test_control_plane_health.py`,
4); rate-limit fail-closed and 503 paths
(`test_security_event_instrumentation.py`); metrics/telemetry
(`test_phase5_observability.py`, `test_phase6_observability_runtime.py`); W3C
traceparent (`test_observability_otel.py`).

This pass added `tests/test_platform_boundary_services.py` (**14 tests**) closing
the remaining verified boundary gaps. Verified: new suite **14 passed**; combined
platform run (new + existing infrastructure + control-plane health) **27 passed**;
neighbouring observability/contract suites (phase5 + openapi + otel) **18 passed**.

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 124 | Configuration Service | `RuntimeConfig.from_env` (URL/credential/port/limit validation) | TESTED (existing) | `test_platform_infrastructure_services.py` |
| 125 | Feature Flag Service | `FeatureFlagService` tenant/env/global override precedence | TESTED (gap closed) | New: tenant override isolation, unknown-flag and non-boolean rejection |
| 126 | Health Check Service | `health.build_health_payload` + `sanitize_health_payload` | TESTED (existing) | `test_readiness_hardening.py` + control-plane health |
| 127 | Readiness Service | `fastapi_app./ready` (sanitized, 503 on degraded) | TESTED (existing) | `test_control_plane_health.py` |
| 128 | Liveness Service | `fastapi_app./live` (never probes dependencies) | TESTED (existing) | dependency-probe assertion pinned |
| 129 | Version Service | `VersionService.snapshot` (release sanitization, `no-store`) | TESTED (existing) | unit + both HTTP surfaces |
| 130 | Request Context Service | `RequestContextService.create` (bounded untrusted IDs, trusted tenant only) | TESTED (gap closed) | New: invalid `X-Request-ID` not echoed by the response; context not leaked across sequential requests |
| 131 | Request ID / Correlation Service | `establish_correlation_context` + `inject_correlation_id` (dashboard `before_request`/`after_request`) | TESTED (gap closed) | New: invalid ID replaced, valid echoed, correlation falls back to request ID |
| 132 | Rate Limiting Service | `TokenBucketRateLimiter` + `rate_limit` decorator + `_get_client_identifier` | TESTED (gap closed) | New: buckets isolated per client and per endpoint; client identifier composes the tenant so tenants sharing an IP get independent buckets |
| 133 | Caching Service | `CacheService` (tenant-isolated keys, bounded, JSON-only, Redis fallback) | TESTED (existing) | `test_platform_infrastructure_services.py` |
| 134 | Distributed Lock Service | `DistributedLockService` (TTL/wait validation) | TESTED (existing) | `test_distributed_lock.py` + validation tests |
| 135 | Observability Service | `logging_utils` context bind/clear + `observability` spans | TESTED (gap closed) | New: cross-request context isolation pinned over the HTTP surface |
| 136 | Metrics Service | `metrics.build_metrics_payload` / `telemetry_snapshot` / `record_*` | TESTED (existing) | alerting/phase5/phase6 suites |
| 137 | Tracing Service | `observability.build_traceparent` / `parse_traceparent` / `trace_span` | TESTED (existing) | `test_observability_otel.py` |
| 138 | Logging Service | `logging_utils.JsonFormatter` (single-line JSON envelope) | TESTED (gap closed) | New: newline message cannot split the envelope (log forging), correlation fallback, whitelist-only observability fields |

### Gaps closed in this pass (additive only)

Added `tests/test_platform_boundary_services.py` (**14 tests**), all behaviours
probed against the running implementation first (Rule #62):
- **Response header reflection** (130/131): an invalid `X-Request-ID` (>128 chars
  or outside `[A-Za-z0-9._:-]`) is *not* echoed back; a valid one is echoed
  unchanged. Newlines are additionally blocked at the transport layer by Werkzeug
  (defense in depth, Rule #16).
- **Context isolation** (130/131/135): `before_request` clears and
  `teardown_request` purges the observability context, so a headerless request
  cannot observe the previous request's tenant/trace IDs; correlation falls back
  to the validated request ID when its own header is absent.
- **Rate-limit tenant isolation** (132): buckets are isolated per client and per
  endpoint, and `_get_client_identifier` composes the authenticated tenant
  (`tenant_tenant-a_<ip>` vs `tenant_tenant-b_<ip>`), so tenant-a traffic cannot
  exhaust tenant-b's allowance (Rule #11).
- **Feature-flag tenant isolation** (125): an override for tenant-a is invisible
  to tenant-b and to the default tenant; unknown flags and non-boolean values are
  rejected.
- **Log forging** (138): `JsonFormatter` escapes newlines so a message containing
  `\n` never splits the single-line JSON envelope; only whitelisted
  `OBSERVABILITY_FIELDS` reach the envelope.

**14 passed.** No test-authoring corrections were needed this pass — every probe
returned the expected fail-closed behavior on the first run.

### Self-review (platform infrastructure phase)

- Integrates with existing architecture: yes — tests drive the shipped dashboard
  app HTTP surface and existing service classes; no new wiring, no production code.
- Preserves tenant isolation: yes — rate-limit bucket keys and feature-flag
  overrides proven tenant-scoped (Rules #11, #13).
- Preserves data integrity: yes — read-only surfaces; no state mutated except
  in-test buckets/flags.
- Race conditions: none introduced (limiter buckets use their existing lock;
  tests are single-threaded; existing concurrency test for flags unchanged).
- Security boundary: strengthened — header reflection, log forging, and
  cross-request context leakage are now pinned (Rules #16, #17).
- Duplicate functionality: no — additive tests only; existing suites untouched.
- Unnecessary network calls: none — Flask test client and in-process services.
- Scalability bottleneck: none introduced.
- Observability: unchanged; tests assert the observability contract itself.
- Tests: added (14), all passing; combined platform + neighbouring suites green
  (27 + 18).
- Rollback/recovery: N/A — no production code changed in this pass.


## Database / data infrastructure services (139–148)

Services 139–148 already carry broad coverage from earlier suites:
`test_database_data_services.py` (DatabaseService sessions/transactions/pool,
Redis URL validation and client ownership, migration upgrade/revision validation,
archival path-traversal/tamper/idempotency),
`test_postgres_concurrency.py`, `test_partitioning.py`,
`test_schema_compatibility.py` + `test_schema_registry.py` +
`test_schema_validation.py` + `test_api_schema_validation.py`,
`test_retention_cleanup.py` + `test_retention_policy.py`,
`test_archival.py`, `test_backup_integrity.py`, `test_disaster_recovery.py`,
`test_phase8_backup_dr.py` (manifests, PITR guards, restore drills),
`test_phase4_event_bus.py` (producer/consumer) and `test_redis_cache.py`.

This pass added `tests/test_database_data_boundary.py` (**14 tests**). Verified:
new suite **14 passed**; sibling suites (data-quality + schema + database-data +
lifecycle + state-machine) green.

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 139 | PostgreSQL Data Service | `DatabaseService` sessions/transactions/pool; tenant-scoped operational and ledger tables | PARTIALLY TESTED | Disposable PostgreSQL 16 catalog audit found 83 tenant-bearing tables and zero missing/unenforced RLS policies. Registry role boundary and tenant/scheduler tests passed. Migration `20261018_transaction_ingestion_runtime_grants` adds audited least-privilege grants for `EventStore.mark_processed`; webhook, scheduler and ledger role tests pass. Remaining runtime table operations and target deployment privileges still require table-by-table audit/verification |
| 140 | Database Migration Service | `MigrationService` (Alembic upgrade/downgrade) | TESTED (gap closed) | Existing input-validation tests plus disposable PostgreSQL 16 full-chain upgrade; ledger and webhook-grant revisions downgrade/re-upgrade successfully. Re-run full upgrade/downgrade verification for head `20261018_transaction_ingestion_runtime_grants` |
| 141 | Connection Pool Service | pool snapshot/metrics, engine settings | TESTED (existing) | pool metrics cases |
| 142 | Redis Service | `RedisService` (URL validation, bounded client, ownership) | TESTED (gap closed) | New: non-success ping raises; non-positive timeouts rejected |
| 143 | Kafka/Redpanda Producer Service | `producer.publish_versioned_event` / `build_event` | TESTED (existing) | phase4 + contract + realtime suites |
| 144 | Kafka/Redpanda Consumer Service | `EventConsumer` / `ConsumerGroup` / `validate_no_consumer_conflicts` | TESTED (existing) | phase4 validation + obs suites |
| 145 | Schema Evolution Service | `schema_evolution` version extraction + compatibility + `DataProvenance` gate | TESTED (**defect fixed**, see below) | New: 8 unit tests + 3 integration regression tests (previously **zero** direct tests) |
| 146 | Data Retention Service | `retention_cleanup` bounded batches, fail-closed policy loading, external approval verification/consumption and immutable audit events | PARTIALLY TESTED | `test_retention_cleanup.py`, `test_retention_policy.py`, `test_retention_approval.py`; destructive runs require approval bound to tenant, effective policy hash, and expiry. Discrepancy and reconciliation-outbox cleanup now fail closed when no separate discrepancy period is configured; policy-owner sign-off is still missing. Transaction purge is gated because production object archival and end-to-end integrity verification are not wired |
| 147 | Data Archival Service | `ArchiveManager` / `ArchiveObjectStore` local implementation | PARTIALLY TESTED | Local path safety, tamper detection, and idempotency tests pass. No production object-store adapter or scheduled transaction archival caller is wired; `mark_transaction_archived` records a key but does not verify its object |
| 148 | Backup/Recovery Service | `backup_postgres` manifests, integrity, PITR, restore drills | PARTIALLY TESTED | `test_backup_integrity.py`, `test_disaster_recovery.py`, `test_phase8_backup_dr.py`, and `test_restore_safety.py`; target guards and manifest checks are tested. Production off-site delivery, live PostgreSQL restore/PITR drills, and measured RPO/RTO remain unverified |

### Defect found and fixed (services 145; human-approved fix under Rule #61)

`DataProvenance.process` fed the raw payload dict into
`assess_schema_compatibility(current_version, incoming_version)` as the *current
version* and compared the returned `(compatibility, note)` tuple to the string
`"incompatible"`. Net effect: **every** data-quality-valid payload crashed with
`AttributeError: 'dict' object has no attribute 'startswith'`, and the schema
quarantine branch could never fire even if the crash were bypassed. Confirmed
empirically before the change; `schema_evolution.py` had zero direct test
coverage and no existing test exercised `process()` past a passing quality gate.

Minimal 3-insertion/1-deletion fix in `provenance.py`: call with the correct
argument order and unpack the tuple. After the fix a DQ-valid payload with an
unknown version (`9.9`) is quarantined with reason `schema_incompatible` rather
than crashing; a minor version (`1.1`) is accepted and anchored to the
processing version; a missing version still fails the quality gate first (fail-
closed ordering, reason `schema version is missing or empty`).

### Gaps closed in this pass (additive only)

Added `tests/test_database_data_boundary.py` (**14 tests**), all behaviours
verified against the running implementation first (Rule #62):
- **Schema Evolution unit contract** (145): compatible / migration_required /
  incompatible; unknown versions never resolve to "compatible"; version
  extraction across all declared keys, numeric coercion, and `version_pair`.
- **Schema quarantine regression** (145): incompatible → quarantined (1 row,
  `schema_incompatible`); minor → accepted + anchored; missing → quarantined by
  the quality gate first.
- **Migration validation** (140): empty URL and missing config file rejected;
  empty downgrade revision rejected.
- **Redis validation** (142): non-success ping raises; non-positive timeouts
  rejected.

**14 passed.**

### Self-review (database/data infrastructure phase)

- Integrates with existing architecture: yes — tests use the shipped
  `schema_evolution` helpers, `DataProvenance`, `MigrationService`, and
  `RedisService`; the 3-line fix follows the existing call convention.
- Preserves tenant isolation: yes — quarantine/provenance are tenant-scoped;
  nothing in this pass touches tenant filtering.
- Preserves data integrity: yes — the fix makes invalid-version outcomes
  *durable* (quarantine row) instead of crashing; quarantines are reversible.
- Race conditions: none introduced (synchronous gate; existing concurrency
  tests untouched).
- Security boundary: strengthened — schema gate restored; previously crashing
  payloads now have auditable dispositions (Rules #10, #16).
- Duplicate functionality: no — additive tests plus the minimal fix.
- Unnecessary network calls: none — in-process/tmp-DB tests.
- Scalability bottleneck: none introduced.
- Observability: quarantine reasons recorded; unchanged structured logs.
- Tests: added (14), all passing; sibling suites green.
- Rollback/recovery: N/A (tests) — the fix itself makes gate outcomes
  revertible per quarantined row rather than exceptional.

## Production operations services (149–156)

The focused production-operations regression selection exercised service
discovery, provider circuit breakers, retries, incident handling, alert routing,
and recovery. The final focused regression run from the backend project root
returned **137 passed** with one existing RQ callback deprecation warning. The
deployment configuration tests use an isolated temporary metrics-token file;
they do not use production credentials or deploy services. This is local
automated evidence; no target deployment or live Redis/Kafka/Alertmanager
certification was performed.

| # | Service | Implementation | Status | Evidence and remaining gap |
|---|---------|----------------|--------|----------------------------|
| 149 | Service Discovery | `service_discovery.ServiceDiscovery` uses Redis TTL leases and atomic scripts; in-memory mode is limited to non-production | TESTED | `tests/test_service_discovery.py`, `tests/test_infra_locking_scheduling.py`; Redis behavior uses a fake client, not a live cluster |
| 150 | Deployment Service | Docker Compose, CI, and deployment configuration; no runtime deployment-service API | PARTIALLY TESTED | `infra/tests/test_configuration.py` validates isolated Compose interpolation; no actual deployment or staged rollback was run |
| 151 | Graceful Shutdown Service | `reconciliation_job.py` signal handler stops the worker loop and independently attempts Kafka consumer/producer cleanup | PARTIALLY TESTED | `tests/test_alerting_monitoring.py` tests signal-driven exit and cleanup after a close failure; bounded in-flight drain/deadline behavior remains unverified |
| 152 | Circuit Breaker Service | Separate Kafka producer and per-provider breakers protect different downstream boundaries | TESTED | `tests/test_communications_intelligence.py`, `tests/test_dashboard_api.py`; live broker/provider recovery is unverified |
| 153 | Timeout Management Service | Per-client timeout settings for Redis, HTTP/provider calls, and workers; no single global timeout budget | PARTIALLY TESTED | Adapter-specific timeout checks exist; end-to-end deadline propagation is not verified |
| 154 | Retry/Backoff Service | `utils.retries.retry_with_backoff`, `event_bus.RetryPolicy`, and durable event retry handling | TESTED | `tests/test_async_job_reliability.py`, `tests/test_phase4_event_bus.py`; actual external outage behavior remains deployment-dependent |
| 155 | Disaster Recovery Service | Backup manifests, restore validation, PITR guards, and restore-drill tooling | TESTED (restore-target safety hardened) | `tests/test_restore_safety.py`, `tests/test_disaster_recovery.py`, `tests/test_backup_integrity.py`, `tests/test_phase8_backup_dr.py`; restore CLI requires explicit confirmation, restore drills reject the configured app database, and reports omit credentials. Target PostgreSQL restore/PITR evidence remains environment-dependent |
| 156 | Incident/Alert Management Service | Incident lifecycle/fingerprinting, alert delivery, routing, Prometheus rules, and escalation | TESTED | `tests/test_communications_intelligence.py`, `tests/test_alerting_monitoring.py`, `tests/test_alert_delivery_routing.py`; live Alertmanager test requires an external service |

### Self-review (production operations phase)

- Architecture integration: existing discovery, worker, retry, alert, and restore
  modules are reused; no new runtime boundary was added.
- Tenant isolation and data integrity: incident and event evidence includes
  tenant-scoped processing; no tenant or persistence behavior changed in this
  verification pass.
- Race conditions: Redis registration/heartbeat transitions use atomic scripts;
  in-memory discovery and circuit state use locks. Graceful in-flight draining
  remains unverified.
- Security boundary: production service discovery fails closed without Redis;
  local fallback is limited to non-production. Live dependency protections were
  not exercised.
- Duplication: the Kafka and provider circuit breakers are separate existing
  controls for different transports; neither was replaced or added here.
- Network and scalability: verification used local tests/fakes; no live network
  load or production capacity claim is made.
- Observability: worker shutdown logs, alert delivery metrics, and incident
  lifecycle evidence exist; live alert delivery is unverified.
- Tests and recovery: the scoped suites are listed above. Backup and restore
  unit/integration drills pass locally; production restore remains unverified.
- Rollback: no schema changed; the shutdown cleanup change is local and
  revertible. Deployment and migration rollback still require a target-like
  rehearsal. A failed resource close is logged, and cleanup continues for the
  remaining resource.

## API governance services (157–162)

The API governance services use the existing contract policy and compatibility
comparator. The focused governance suite passed 9 tests; the related dashboard,
OpenAPI, schema, and callback regression selection passed 60 tests. Local test
results do not verify a deployed API or certify production readiness.

| # | Service | Implementation | Status | Evidence |
|---|---------|----------------|--------|----------|
| 157 | API Versioning Service | `api_governance.ApiVersioningService` reuses `contract_versioning` policy for explicit `/api/vN` and `/vN` paths | TESTED | `tests/test_api_governance.py`; supported and unsupported version behavior through dashboard/auth Flask clients |
| 158 | API Contract Service | `api_governance.ApiContractService` exposes supported/deprecated versions and validates OpenAPI semver against policy | TESTED | `tests/test_api_governance.py`; static and runtime contract checks |
| 159 | API Deprecation Service | `api_governance.ApiDeprecationService` emits headers only for explicitly configured deprecated versions | TESTED | `tests/test_api_governance.py`; sunset timezone and successor-link validation |
| 160 | API Compatibility Service | `api_governance.ApiCompatibilityService` delegates comparison to `schema_compatibility.compare_openapi` | TESTED | `tests/test_api_governance.py`, `tests/test_schema_compatibility.py`, `tests/test_contract_diff.py` |
| 161 | OpenAPI Specification Service | `api_governance.OpenAPISpecService` loads and validates the authoritative contract and local references | TESTED | `tests/test_api_governance.py`, `tests/test_openapi_contract.py`, `tests/test_internal_api_contract.py` |
| 162 | API Error Standardization Service | `api_governance.ApiErrorService` enriches JSON API errors with RFC 9457 fields and retains PesaGuard fields | TESTED | `tests/test_api_governance.py`; dashboard/auth HTTP error integration and webhook callback-format regression |

### Self-review (API governance phase)

- Architecture integration: reuses `contract_versioning.py` and
  `schema_compatibility.py`; no new routing framework or external dependency.
- Tenant isolation and financial data integrity: no tenant or persistence
  behavior changed; existing route authorization remains the boundary.
- Race conditions: no shared mutable request state added by governance helpers.
- Security boundaries: unsupported versions fail closed; local OpenAPI refs are
  validated; webhook provider callback payloads retain their existing format.
- Duplication and network calls: shared comparator and error helper are reused;
  checks add no network calls.
- Performance: a local microbenchmark measured 100,000 URL-version checks at
  0.247 seconds and 100 full OpenAPI load/validation checks at 0.198 seconds on
  the development machine. This is not a production SLO or deployment benchmark.
- Observability and tests: existing request IDs are preserved; focused suites
  passed (9 new tests and 60 related regressions).
- Rollback/recovery: no persistence changes or migrations; source changes can be
  reverted. Runtime version policy remains centralized in the existing contract
  policy.

## Transaction ingestion hardening (2026-10-13)

**Service 12 / Daraja ingestion:** retained `IngestionService`, `EventStore`,
the durable transaction outbox, versioned M-Pesa contract, batch import jobs,
and tenant-scoped dead letters. Closed gaps by requiring a separately keyed raw
body HMAC on Daraja callback routes; persisting callback verification/source
metadata, attempt counts, source schema version and source digest; enforcing
immutable source data in ORM and database triggers (with the authorized privacy
redaction exception); making validation DLQ writes observable and retryable;
re-ingesting validation dead letters through the canonical idempotent path;
and leasing/restarting bounded historical imports.

- Implementation: `app.py`, `ingestion.py`, `event_store.py`, `models.py`,
  `transaction_routes.py`, `batch_ingestion.py`, `background_tasks.py`,
  `api/dashboard_app.py`, `health.py`, migration
  `alembic/versions/20261013_harden_transaction_ingestion.py`.
- Contract and deployment notes: `docs/transaction-ingestion.md`.
- Focused coverage: `tests/test_transaction_ingestion_hardening.py`,
  `tests/test_transaction_ingestion_migration.py`,
  `tests/test_batch_ingestion.py`, `tests/test_async_job_reliability.py`,
  plus the existing Daraja schema, signature, route, and traceability suites.
- Latest focused regression run: **72 passed** across ingestion, migration,
  readiness, dead-letter operations, imports, and replay. The separate
  `test_versioned_auth_aliases_are_registered` check still returns HTTP 503
  where it expects HTTP 401; that unrelated auth-alias failure remains
  unresolved. Readiness now remains degraded until webhook signing, source
  authentication, and tenant configuration are present, without exposing
  secret values.
- Database migration evidence: the new revision's SQLite upgrade and database
  immutability/redaction trigger were exercised directly. A full SQLite upgrade
  from the initial Alembic revision remains blocked by an earlier migration's
  unavailable SQLite `md5()` function, before this revision is reached.
- Performance/capacity: no representative production benchmark or approved
  throughput threshold was available; production capacity is NOT VERIFIED.
- External deployment: the live callback sender must provide
  `X-Daraja-Signature` as HMAC-SHA256 over exact raw bytes, and deployment must
  configure `DARAJA_WEBHOOK_SIGNING_SECRET`, source authentication,
  `TENANT_ID`, durable shared import storage, database migration, and queue
  workers. None of those live dependencies were verified here.
- Rollback: revert application code while retaining the additive migration.
  Downgrade removes provenance and import retry metadata and requires an
  explicit data-recovery decision. This implementation is not certified
  production-ready until target-like PostgreSQL, sender, queue, and backfill
  validation passes.
