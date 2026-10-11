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
| 6 | Tenant | `tenant_organization_service.py` + `TenantConfiguration/Limit/Usage` | TESTED | `test_tenant_organization_service.py`, `test_tenant_scope_constraints.py` |
| 7 | Project | `project_environment_service.py` `ProjectService` | TESTED | `test_project_environment_service.py` |
| 8 | Environment | `project_environment_service.py` `EnvironmentService` (tier promotion) | TESTED | `test_project_environment_service.py` |
| 9 | API Key | `models.py` `ApiKeyRecord`, `ApiKeyLifecycleIdempotencyRecord`, `ServiceIdentity`; Java `ApiKeyService`; key-sync bridge | TESTED | `test_developer_api_key_sync.py`, `test_api_idempotency_contract.py` |
| 10 | Session / Device | `UserSession`, `DeviceIdentity`; session routes; Java `SessionService` | TESTED | `test_session_management.py`, `test_device_risk_auth.py` |
| 11 | OTP / MFA | `MFAChallenge/Factor/Recovery/Policy/Event`; Java `MfaService`/`TotpService`; `communications` OTP | TESTED | `test_otp.py`, `test_device_risk_auth.py`, `test_iam_foundation.py` |

## Transaction services (12–20)

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 12 | Transaction Service | `models.Transaction` + `IngestionService` + `EventStore.mark_processed` + `POST /api/v1/transactions` | TESTED | `test_transaction_routes.py`, `test_transaction_outbox.py`, `test_api_idempotency_contract.py`, `test_ingestion.py` |
| 13 | Transaction Status Service | `EventStore.update_processing_status` + `GET /transactions/{id}/status` | TESTED | `test_transaction_routes.py`; optimistic-locking covered by `test_transaction_state_machine.py` |
| 14 | Payment Service (origination) | **Out of scope** | NOT IMPLEMENTED (by design) | `PRODUCT_SCOPE.md`: no payouts/fund movement. Only Daraja callback *ingestion* exists |
| 15 | Payment Confirmation Service | **Out of scope** | NOT IMPLEMENTED (by design) | `PRODUCT_SCOPE.md`: "does not execute or confirm settlements" |
| 16 | Refund Service (money movement) | **Out of scope** | NOT IMPLEMENTED (by design) | Refund/reversal exists only as reconciliation *classification*, not money movement |
| 17 | Payment / Transaction State Machine | `lifecycle.transition_transaction` + DB `CheckConstraint` + `EventStore.transition_transaction` | TESTED (gap closed this pass) | New: `test_transaction_state_machine.py` — see below |
| 18 | Transaction History Service | `TransactionEvent` (append-only) + `_reconciliation_payload` history | TESTED | `test_transaction_routes.py`, `test_transaction_state_machine.py` (state-change events) |
| 19 | Transaction Search Service | `/transactions/search` (tenant-scoped, paginated, LIKE-escaped) | TESTED (gap closed this pass) | New: `test_transaction_search_security.py` — see below |
| 20 | Transaction Idempotency Service | `IdempotencyRecord` + `ProcessedTransaction` ledger + `_idempotent_route` | TESTED | `test_api_idempotency_contract.py`, `test_transaction_outbox.py`, `test_transaction_routes.py` |

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

3. **Performance baseline (services 12, 13, 17, 20) — no measured throughput test.**
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

## Data pipeline services (21–32)

All twelve services are implemented and covered by dedicated test suites. Verified
by running the existing suites (baseline): normalization/schema/contract (18),
ingestion/batch/lineage/catalog/dictionary (19), schema-registry/source-contracts/
transformation/data-quality (9), core-data-engineering/realtime (6) — all green.

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
| 30 | Data Quality Service | `data_quality.py`, `dq_rules.py` | TESTED | `test_data_quality_layer.py` (dimension scoring + quarantine) |
| 31 | Data Lineage Service | `lineage.py`, `data_lineage.py`, `provenance.py` | TESTED | `test_lineage.py` |
| 32 | Data Metadata Service | `data_catalog.py`, `data_dictionary.py`, `transformation_store.py` | TESTED | `test_data_catalog.py`, `test_data_dictionary.py`, `test_transformation_store.py` |

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
services are implemented and covered. Verified by running existing suites:
reconciliation/utils/state/batch/phase3/ground-truth/anomaly (31) and settlement
(10) — all green, including the phase-3 golden-dataset 100%-correct certification.

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 43 | Reconciliation Service | `reconciliation_engine.py` `ReconciliationEngine.reconcile` | TESTED | `test_phase3_reconciliation.py` (golden dataset), `test_reconciliation.py` |
| 44 | Reconciliation Job Service | `reconciliation_job.py` | TESTED | `test_reconciliation_batch.py` |
| 45 | Transaction Matching Service | `reconciliation_engine.py` matching rules + `reconciliation_scoring.score_match` | TESTED (gap closed) | New: `test_reconciliation_determinism.py` |
| 46 | Exception Service | engine `EXCEPTION` outcomes + `discrepancy_dao.py` | TESTED | `test_phase3_reconciliation.py` (invalid input, sub-cent) |
| 47 | Unmatched Transaction Service | engine `UNMATCHED`/`MISMATCH` outcomes | TESTED | `test_reconciliation_determinism.py`, `test_phase3_reconciliation.py` |
| 48 | Reconciliation Rules Service | engine matching-rule tags + `anomaly_rules.py` | TESTED | `test_anomaly_rules.py`, `test_phase3_reconciliation.py` |
| 49 | Reconciliation Result Service | engine `_result` evidence + `reconciliation_scoring` | TESTED | `test_anomaly_scoring.py`, `test_phase3_reconciliation.py` |
| 50 | Reconciliation Audit Service | `record_ground_truth`, `ReconciliationGroundTruth`, `action_audit.py` | TESTED | `test_ground_truth_certification.py` (immutability) |

### Determinism gap found and fixed (services 43, 45, 47)

**Finding (Rule #26/#27):** when multiple internal records tied on match score,
candidate selection used `max(records, key=score)`, which returns the FIRST max in
input order. I verified with a live probe that reversing the input changed which
record a callback reconciled against (`cand-A` vs `cand-C`) — a non-deterministic,
non-explainable financial decision. Distinct-score cases were already deterministic.

**Fix (human-approved per Rule #61):** added `_best_candidate`, which selects the
highest-scoring record and breaks score ties with a stable secondary key
(`internal_ref`, ascending). When scores differ nothing changes (higher score still
wins); ties are now reproducible regardless of input order. This is the minimal
change that restores determinism without altering normal-case outcomes.

**Verification (Rule #37/#38):**
- Added `tests/test_reconciliation_determinism.py` (11 tests): tied candidates
  resolve identically under reordering, ties break by `internal_ref`, distinct
  scores still pick the better candidate, and the key Rule #27 cases (exact,
  unmatched, duplicate-reference, mismatch, refund-requires-original, invalid
  amount) remain stable.
- Full reconciliation suite re-run: **42 passed** (11 new + 31 existing), including
  the golden-dataset 100%-correct certification — **zero regressions**.
- Settlement suite re-run: **10 passed**.

### Self-review (reconciliation phase)

- Integrates with existing architecture: yes — one helper added inside the engine.
- Preserves tenant isolation: reconciliation operates on caller-supplied records;
  no tenant boundary changed.
- Preserves data integrity: yes — determinism strengthened; no match outcome
  changed for distinct scores.
- Race conditions: none introduced (pure selection).
- Security boundary: unchanged.
- Duplicate functionality: no — reuses existing `_score`.
- Unnecessary network calls: none (in-memory).
- Scalability bottleneck: `_best_candidate` is O(n), same as `max`; `_score` is
  called per candidate as before.
- Observability: unchanged (result evidence unchanged in shape).
- Tests: added (11), all passing; existing reconciliation + settlement still green.
- Rollback/recovery: change is a pure, in-memory selection refinement; revertable by
  restoring the two `max(...)` lines. **Human-approved** because it touches
  reconciliation matching logic (Rule #61).

## Settlement services (51–55)

**Important scope nuance (Rule #47):** `settlement.py` implements an **internal
settlement calculation / status / reconciliation / reporting domain**. It computes
and tracks settlement *records* against internal ledger data using `Decimal` money,
tenant isolation, per-key idempotency, and an auditable state machine. It does
**NOT** move money, call Daraja, initiate payouts/B2C, or execute fund transfers —
so it respects the money-movement boundary in `PRODUCT_SCOPE.md`. It is currently
imported only by tests (no HTTP route or worker wires `SettlementService` in yet).

Verified by running `tests/test_settlement.py`: **10 passed** (money math, per-key
idempotency, calculate→reconcile→settle lifecycle, variance→`disputed`, cross-tenant
denial, same-key-per-tenant isolation, illegal/terminal transition rejection,
tenant-scoped reporting).

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 51 | Settlement Service | `settlement.py` `SettlementService` (facade) | TESTED | `test_settlement.py` lifecycle |
| 52 | Settlement Calculation Service | `SettlementCalculationService` (Decimal half-up totals; idempotent per `(tenant_id, settlement_key)`) | TESTED | money math + idempotency |
| 53 | Settlement Status Service | `SettlementStatusService` (auditable state machine) | TESTED | illegal/terminal transitions rejected |
| 54 | Settlement Reconciliation Service | `SettlementReconciliationService` (variance = expected − net; non-zero → `disputed`, never overwritten) | TESTED | variance handling |
| 55 | Settlement Reporting Service | `SettlementReportingService` (read-only, tenant-scoped, Decimals as strings) | TESTED | tenant-scoped listing |

### Documentation conflicts found (flagged for human decision; NOT changed here)

Per Rule #47 (documentation must describe the real system) and Rule #60, I am
**reporting** these rather than editing scope/decision documents unilaterally:

1. **`docs/product/PRODUCT_SCOPE.md` is stale.** It states there is "no corresponding
   settlement persistence model, executable API, or worker workflow." This is now
   contradicted by `settlement.py`, the `Settlement`/`SettlementLine` models in
   `models.py`, the Alembic migration `20261009_add_settlement_scheduler.py`, and
   `docs/domains/reconciliation/SETTLEMENTS.md` (which was updated 2026-10-09 to
   describe the implemented services). **Recommendation:** update `PRODUCT_SCOPE.md`
   to state that an *internal, non-money-moving* settlement calculation/reporting
   domain exists, while retaining the explicit boundary that PesaGuard does not
   execute settlements, payouts, or fund movement.

2. **`settlement.py` module docstring overstates observability.** Line ~13 says
   illegal transitions "are recorded as security events," but the code raises
   `SettlementStateError` and logs at INFO without calling `record_security_event`.
   **Recommendation:** either add the security-event call (Rule #40) or correct the
   docstring. Left unchanged here because it touches production behavior.

### Self-review (settlement phase)

- Integrates with existing architecture: yes — services use existing models/session.
- Preserves tenant isolation: yes — every query/mutation is tenant-scoped; DB check
  constraints enforce non-empty tenant_id (verified by `test_settlement.py`).
- Preserves data integrity: yes — `Decimal`/`NUMERIC(18,2)`, half-up rounding,
  non-negative check constraints, idempotent per key.
- Race conditions: idempotency per `(tenant_id, settlement_key)`; unique constraint
  prevents double-count on replay.
- Security boundary: unchanged; no money movement added.
- Duplicate functionality: no — reuses existing models.
- Unnecessary network calls: none (pure DB).
- Scalability bottleneck: none introduced; tenant/period indexes exist.
- Observability: structured logging present; **security-event recording is a flagged
  gap** (see above).
- Tests: existing suite green (10); no new tests required — coverage is thorough.
- Rollback/recovery: N/A — no production code changed in this pass.

## Risk & fraud services (56–63)

Rule #28 is the governing constraint: fraud detection must distinguish
anomaly / suspicion / high-risk / confirmed fraud and must **never** auto-declare
fraud from a score alone. The engine complies — its highest action is `escalate`
(human review); there is no `confirm_fraud` action.

Verified by running existing suites: fraud-risk-engine / anomaly-rules /
anomaly-scoring / escalation-actions / alert-delivery-routing / alerting-monitoring
— **32 passed** (incl. 5 new boundary tests).

| # | Service | Implementation | Status | Notes |
|---|---------|----------------|--------|-------|
| 56 | Risk Service | `fraud_risk_engine.assess_transaction` + `FraudRiskAssessment` model | TESTED | `test_fraud_risk_engine.py`, `test_fraud_boundaries.py` |
| 57 | Anomaly Detection Service | `anomaly_rules.check_for_anomalies` / `score_transaction_anomaly` | TESTED (gap documented) | `test_anomaly_rules.py`, `test_fraud_boundaries.py` |
| 58 | Fraud Detection Service | `fraud_risk_engine` layered rules + `fraud_events.py` publication | TESTED | `test_fraud_risk_engine.py` (Rule #28 boundaries) |
| 59 | Risk Scoring Service | `fraud_risk_engine` features/score; `reconciliation_scoring.score_match` | TESTED | `test_anomaly_scoring.py`, `test_fraud_risk_engine.py` |
| 60 | Risk Rules Service | `fraud_risk_engine.evaluate_rules` (velocity/amount/duplicate/time/rapid) | TESTED | explainable `rules_triggered` |
| 61 | Risk Event Service | `fraud_events.py` (`publish_fraud_analysis`, `publish_fraud_review_decision`) | TESTED | versioned Kafka events; analyst-attributed verdict |
| 62 | Investigation Service | `escalation_engine.py`, `on_call_service.py`, `discrepancy_dao.py` | TESTED | `test_escalation_actions.py` |
| 63 | Alert Service | `alerting_service.py`, `notifier.py`, `alert_template_loader.py` | TESTED | `test_alert_delivery_routing.py`, `test_alerting_monitoring.py` |

### Rule #28 boundary pinned + defense-in-depth gap documented (additive)

Added `tests/test_fraud_boundaries.py` (5 tests):
- No action ever confirms fraud (highest = `escalate`); risk levels are the four
  documented tiers; a pathological amount yields a bounded, non-NaN score.
- The primary ingestion normalizer rejects NaN/Infinity, so non-finite amounts never
  reach anomaly scoring through normal ingestion.
- **Flagged defense-in-depth gap:** the secondary `anomaly_rules` path does NOT flag
  a literal `"nan"` amount (it silently scores 0.0), even though it flags negative
  and infinite amounts. Unreachable via normal ingestion (normalizer blocks it), but
  pinned in a test so the behaviour is explicit. **Recommendation for human
  decision:** either harden `anomaly_rules._has_invalid_amount`/scoring to treat
  non-finite as invalid, or document the reliance on the ingestion normalizer.

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
| 81 | Scheduler Service | `scheduler.py` `Scheduler`: durable `scheduled_jobs`, injected-clock due computation, distributed-lock per run | TESTED | idempotent register, deterministic due, at-most-once, tenant isolation, failure+rearm |
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
| 69 | Webhook Management Service | `webhook_manager.WebhookManager` register/get/update + `_validate_webhook_url` (SSRF) | TESTED (gap closed) | New: `test_webhook_management_security.py` |
| 70 | Webhook Delivery Service | `_deliver_webhook` with delivery-time SSRF re-validation, no-redirect policy, tenant-scoped audit rows | TESTED (gap closed) | DNS-rebinding defense pinned |
| 71 | Webhook Retry Service | exponential backoff (`2**attempt`), bounded by `retry_attempts` (capped at 5) | TESTED (gap closed) | retry-then-succeed, bounded-attempts, timeout-retry |
| 72 | Webhook Signature Service | `_generate_signature`: HMAC-SHA256 over `t=<ts>,v1=<hmac>` with per-tenant `whsec_` secret | TESTED (gap closed) | verify-format, determinism, ts/body/secret binding, header wiring |
| 73 | Event Service | `event_bus.py` versioned contract (`EVENT_TYPES`, `build_event`, schema/event versions) | TESTED | `test_phase4_event_bus.py` |
| 74 | Event Publisher | `producer.publish_versioned_event` + batch publish; broker-unavailable raises without ack | TESTED | per-message delivery results pinned |
| 75 | Event Consumer | `event_consumer.py` + `realtime_worker.py`: idempotent processing, bounded retry, backpressure | TESTED | duplicate-once + crash-retry pinned; `test_realtime_worker.py` |
| 76 | Dead-Letter Queue Service | `dead_letters` table + phase4 DLQ placement; never silently discarded (Rule #10/#31) | TESTED | poison message → DLQ; network failure → DLQ; DLQ retained on backpressure |
| 77 | Event Replay Service | phase4 replay preserves event identity after retry exhaustion | TESTED | replay-without-handler preserves dead letter |

### Webhook gaps closed in this pass (additive only)

Added `tests/test_webhook_management_security.py` (**17 tests**):
- **Service 69:** http/ftp rejected; localhost/metadata-IP/hostname rejected;
  private-resolved-IP rejected (DNS-rebinding class); unique unpredictable
  per-registration `whsec_` secrets; signing secret immutable through `update`;
  get/update strictly tenant-scoped.
- **Service 70:** delivery-time SSRF re-validation aborts delivery; redirect
  responses abort after exactly one request (no redirect-following SSRF/token-leak
  vector); tenant-scoped `WebhookDelivery` audit row with true `attempt_count`.
- **Service 71:** 5xx then 200 succeeds on attempt 2; retries bounded to exactly
  `retry_attempts` (no unbounded loop); timeouts are retryable failures.
- **Service 72:** signature format `t=<ts>,v1=<hmac>` independently verifiable with
  the tenant secret; deterministic; binds timestamp, body, and secret (no
  cross-tenant replay); delivery headers wire signature+timestamp+event.

**17 passed** (run in batches; the full-file run exceeds a 30s command window due
to per-test SQLite schema creation — same characteristic as other suite files).

### Minor observability inaccuracy flagged (not changed)

On a redirect abort, the returned dict reports `"attempts": max_attempts` (the
configured cap) even though only one request was made; the **DB
`attempt_count` correctly records 1**. Recommendation for human decision: report
the true attempt count in the response dict for operator clarity (Rule #40). Left
unchanged here as it touches delivery response semantics.

### Self-review (webhooks & events phase)

- Integrates with existing architecture: yes — tests exercise the existing manager,
  event bus, producer, and consumer modules; no new wiring.
- Preserves tenant isolation: yes — registration/lookup/update/delivery/signature
  all scoped or secret-per-tenant; cross-tenant update returns not-found.
- Preserves data integrity: yes — delivery audit rows written on success and
  failure; no silent drops.
- Race conditions: none introduced (single-threaded test doubles).
- Security boundary: strengthened — SSRF (registration + delivery + redirect),
  signature binding, and secret immutability now pinned by tests.
- Duplicate functionality: no — additive tests only.
- Unnecessary network calls: none — all network mocked; DNS patched.
- Scalability bottleneck: none introduced (tests only).
- Observability: structured logs confirmed during runs; minor `attempts` reporting
  inaccuracy flagged above.
- Tests: added (17), all passing; event/realtime suites still green.
- Rollback/recovery: N/A — no production code changed in this pass.

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
| 139 | PostgreSQL Data Service | `DatabaseService` sessions/transactions/pool | TESTED (existing) | `test_database_data_services.py` |
| 140 | Database Migration Service | `MigrationService` (Alembic upgrade/downgrade) | TESTED (gap closed) | New: empty-URL, missing-file, empty-downgrade-revision rejection |
| 141 | Connection Pool Service | pool snapshot/metrics, engine settings | TESTED (existing) | pool metrics cases |
| 142 | Redis Service | `RedisService` (URL validation, bounded client, ownership) | TESTED (gap closed) | New: non-success ping raises; non-positive timeouts rejected |
| 143 | Kafka/Redpanda Producer Service | `producer.publish_versioned_event` / `build_event` | TESTED (existing) | phase4 + contract + realtime suites |
| 144 | Kafka/Redpanda Consumer Service | `EventConsumer` / `ConsumerGroup` / `validate_no_consumer_conflicts` | TESTED (existing) | phase4 validation + obs suites |
| 145 | Schema Evolution Service | `schema_evolution` version extraction + compatibility + `DataProvenance` gate | TESTED (**defect fixed**, see below) | New: 8 unit tests + 3 integration regression tests (previously **zero** direct tests) |
| 146 | Data Retention Service | `retention_cleanup` bounded batches + `RetentionPolicy` | TESTED (existing) | `test_retention_cleanup.py`, `test_retention_policy.py` |
| 147 | Data Archival Service | `ArchiveManager` / `ArchiveObjectStore` (hash-partitioned, tamper-evident) | TESTED (existing) | path-traversal + tamper + idempotency |
| 148 | Backup/Recovery Service | `backup_postgres` manifests, integrity, PITR, restore drills | TESTED (existing) | `test_backup_integrity.py`, `test_disaster_recovery.py`, `test_phase8_backup_dr.py` |

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
| 155 | Disaster Recovery Service | Backup manifests, restore validation, PITR guards, and restore-drill tooling | TESTED | `tests/test_disaster_recovery.py`, `tests/test_phase8_backup_dr.py`; production PostgreSQL restore/PITR drill is not evidenced here |
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
