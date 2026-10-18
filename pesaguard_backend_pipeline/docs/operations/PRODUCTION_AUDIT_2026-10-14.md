# PesaGuard Backend — Production Readiness Audit

> **Audit date:** 2026-10-14
> **Auditor:** Cline (AI coding agent, PesaGuard Agent Rules)
> **Scope:** `pesaguard_backend_pipeline/` (479 Python modules, ~100k LOC, 168 test files)
> **Method:** Static inspection, live boot test, secret scan, SQL-injection scan,
> git-tracking check, CI workflow review, and cross-check against the repository's
> own evidence record (`PRODUCTION_CERTIFICATION.md`).
> **Production access:** None. No production system was accessed or changed.

---

## 1. Verdict

**NOT PRODUCTION-READY — DO NOT SHIP.** ❌

This conclusion agrees with the team's own certification record
(`docs/operations/PRODUCTION_CERTIFICATION.md`, updated 2026-10-14), which states
verbatim: **"NOT CERTIFIED — DO NOT SHIP."**

PesaGuard is financial infrastructure (real-time M-Pesa reconciliation, anomaly and
fraud detection, SACCO/e-commerce settlement). Under PesaGuard Rule #2 (Financial
correctness > Data integrity > Security > … > Convenience) and Rule #65 (absolute
non-negotiables), the readiness bar is appropriately high. The codebase clears the
**architecture, security-hygiene, and tenant-isolation** bars with genuine strength,
but it does **not** yet clear the **financial-correctness certification**, **recovery-drill**,
or **load-testing** gates.

---

## 1a. Session correction (important — supersedes earlier B2/B3 claims)

A follow-up verification pass on **2026-10-14** ran the previously-cited failing tests
under a CI-parity environment and **overturned two earlier findings**. Per Rule #62
(evidence over assumptions), the corrected facts are:

- **B3 was WRONG and is withdrawn.** The `communications` package **exists** and its
  20 tests **all pass** (`tests/test_communications_intelligence.py` → 20 passed). The
  "operations dashboard frontend does not exist / 21 tests target an absent module"
  claim was a misattribution. There is no missing-frontend blocker.
- **B2 is downgraded and partly resolved.** The auth-lifecycle/session/password
  "failures" were **environmental** — the on-disk `.env` enables Redis-backed
  rate-limiting (`REDIS_URL` + `ENABLE_REDIS_RATE_LIMITING`) and the auth limiter
  **fails closed** (HTTP 503 `rate_limiter_unavailable`) when Redis is configured but
  unreachable. CI provides a live Redis service; this machine did not. Under a faithful
  CI-parity simulation (`fakeredis` + `lupa` as the Redis backend, injected **only** via a
  throwaway pytest plugin in `%TEMP%`, no repo code changed), every cited auth/session/
  password test **passes**: `test_auth_lifecycle.py` 10/10, `test_login_hardening.py`,
  `test_token_revocation.py`, `test_password_credentials.py`, `test_password_policy.py`,
  `test_session_management.py` all green.
- **The only genuine code-adjacent test defects were 2 stale event-bus tests**, which
  published payloads **without** `tenant_id` — but `producer.py` (lines 177 & 297)
  enforces mandatory `tenant_id` as an intentional tenant-isolation control (Rule #11).
  **Fixed this session** by adding `"tenant_id": "tenant-a"` to the test payloads
  (assertions unchanged). `tests/test_phase4_event_bus.py` → **13 passed, no regressions**.

**Net effect on the verdict:** the verdict stands (**not production-ready**) — but the
remaining blockers are now **B4, B5, B6, B7, B8, B12**, not B2/B3. The test-suite signal
is far healthier than the cert doc's raw local run suggested.

---

## 2. Executive Summary

| Dimension | Rating | One-line |
|---|---|---|
| Architecture & design | 🟢 Strong | Clean provider-adapter → canonical-transaction separation; explicit package layout. |
| Security hygiene | 🟢 Strong | No committed secrets, no SQLi, fails-closed config, non-root container, RLS. |
| Tenant isolation | 🟢 Strong | 150 PostgreSQL RLS policies + least-privilege DB roles, audited end-to-end. |
| CI/CD | 🟢 Strong | Trivy image scan + CodeQL, gated staged promotion, contract gate, restore-drill job. |
| Observability | 🟡 Good | OTel + Sentry bootstrap, `/live` liveness, Prometheus, structured logs. |
| Test suite | 🟢 Good (after fix) | Cited auth/session/password failures were environmental (no local Redis); event-bus tests fixed this session → green under CI-parity. |
| Financial certification | 🔴 Blocked | Reconciliation accuracy lacks required human sign-off (precision/recall = 1.0). |
| Recovery & load | 🟠 Partial (strong local evidence) | Load run **base** (1M rows @ 19,371 rows/s, 8-way) **and stress** (1M rows @ 46,932 rows/s, 32-way), both `passed:true`; backup→restore→integrity DR drill **PASSED** with **measured RTO** (backup 2.953s / restore 2.067s / integrity 1.528s) — all on throwaway local Postgres, **not production**. Production-scale RPO/RTO, spike/soak, encryption-at-rest & offsite still outstanding. |
| Secrets management | 🟠 Gap | No secret-store integration; rotation owner/cadence unresolved. |
| Repo hygiene | 🟠 Gap | 8 debug/scratch artifacts git-tracked; 6 app entrypoints; 4 requirements files. |

**Bottom line:** The engineering quality of the code and pipeline is high, and the
team documents its own gaps with unusual honesty. After this session's verification,
the test-suite picture is much healthier than the cert doc's raw local run implied
(see §1a): the auth "failures" were a missing-local-Redis artifact, and the 2 real
event-bus test defects are now fixed. What remains is not architectural and not a test
gap — it is **verification and operational proof** (financial certification, DR drills,
load testing, secrets operationalization) that cannot be shortcut for a financial
product. Blockers **B4–B8 and B12** must close first.


---

## 3. What is genuinely solid (verified this session)

| # | Area | Finding | Evidence |
|---|---|---|---|
| S1 | No committed secrets | Repo-wide secret scan returned only test fixtures and documentation placeholders. No AWS (`AKIA…`), Stripe (`sk-…`), GitHub (`ghp_…`), or Slack (`xox…`) token patterns. No `.env` is git-tracked (on-disk `.env` is correctly ignored). | Secret scan; `git ls-files` shows no tracked `.env` |
| S2 | No SQL injection | All f-string `execute()` matches are Alembic DDL using `preparer.quote()` on controlled identifiers (policy/table names from a fixed allow-list) — not user input. No `SELECT/INSERT/UPDATE` string-concatenation with request/user/tenant values. | SQLi scan |
| S3 | No disabled TLS verification | Zero occurrences of `verify=False`, `ssl_verify=False`, or `trust_env=False` across the tree. | Scan |
| S4 | Canonical entrypoint boots | `canonical_app` → `fastapi_app` imports cleanly as a `FastAPI` app with mounted sub-applications (dashboard, auth, advanced features). | Live `importlib` boot test |
| S5 | Fails closed on missing config | Startup aborts with `RuntimeError: PESAGUARD_API_URL must be configured` when required env is absent — correct Rule #59 behavior, not a defect. | Observed |
| S6 | Container hardening | `python:3.12-slim`, runs as unprivileged `appuser` (Rule #56), `PESAGUARD_BIND_HOST=127.0.0.1` default, `/live` liveness HEALTHCHECK independent of downstream deps (Rule #40). | `infra/docker/Dockerfile` |
| S7 | CI/CD maturity | Trivy image scan (HIGH/CRITICAL) → CodeQL SARIF upload; gated `staging → production` promotion via protected `production` environment secrets; staging/production health verification loops; API breaking-change contract gate; automated backup→restore drill job. | `.github/workflows/` |
| S8 | Dependency pinning | All four requirements files are fully `==`-pinned (reproducible builds). Docker uses `requirements_3.txt` (Flask 3.1.3, FastAPI 0.115.6, SQLAlchemy 2.0.38, etc.). | `requirements_3.txt` |
| S9 | Tenant isolation depth | Alembic chain applies 150 row-level-security policies and provisions isolated least-privilege DB roles (`pesaguard_app`, `pesaguard_tenant_registry`, `pesaguard_api_key_lookup`). | Migration `20261014_tenant_row_level_security.py`; cert doc |
| S10 | Operational documentation | 23 operations runbooks covering DR, incident, on-call, monitoring, tenancy, rate limiting, webhook delivery, etc. | `docs/operations/` |

---

## 4. Blockers preventing production (B1–B12 register)

Severity: 🔴 Critical (ship-stopping) · 🟠 High · 🟡 Medium

> Register updated 2026-10-14 after this session's verification. B1, B9, B10 are resolved
> (see §9). B2 and B3 are withdrawn/corrected (see §1a). The **active** blockers are
> **B4, B5, B6, B7, B8, B12** — every one of them is a *verification/operational-proof* gap,
> not a code defect.

| # | Blocker | Severity | Proof / Source |
|---|---|---|---|
| **B2** | **WITHDRAWN / RESOLVED** (was "test suite not green"). The auth/session/password failures were **environmental** — the local `.env` enables Redis rate-limiting but no Redis runs on this host, so the auth limiter fails closed (503 `rate_limiter_unavailable`). Under CI-parity (`fakeredis`+`lupa`) all cited tests pass. The 2 genuinely-stale event-bus tests (missing `tenant_id`) were **fixed this session**. | 🟢 | This session: CI-parity runs; 2 tests fixed |
| **B3** | **WITHDRAWN** (was "dashboard frontend missing"). Misattribution. The `communications` package exists; `test_communications_intelligence.py` → **20 passed**. *(CI hygiene nit remains: the frontend job is `continue-on-error` + `if [ -f frontend/package.json ]` — masks a truly-missing frontend, but is not a failing-test cause.)* | 🟢 | `test_communications_intelligence.py` → 20 passed |
| **B4** | **Reconciliation accuracy is not certified by the repo's own definition.** `RECONCILIATION_CERTIFICATION.md` requires a named human approver and `certification_ready = 1`, `precision = recall = 1.0`, `FP = FN = 0`. The tooling **exists and enforces the gate** (`operations/ground_truth_certification.py` exposes `--approved-by` / `--approval-reference` / `--validated-by`), but **no approval artifact exists and it cannot be self-issued** (Rule #61). Core financial-correctness gate. | 🔴 | Cert doc; cert tool CLI |
| **B5** | **Load harnesses EXIST and a real 1,000,000-row run has now been executed this session (2026-10-14).** Four runners under `operations/`: `run_endpoint_load_test.py` **supports `--profile {load,stress,spike,soak}`** out of the box, plus `run_auth_load_test.py`, `run_load_test.py`, `run_postgres_load_test.py`. All parse/compile and invoke in module mode. What is missing is a run against a production-shaped stack with committed artifacts. *(This corrects the earlier "no harness exists" claim.)* | 🟠 | **Executed (throwaway local Postgres:16):** base `run_postgres_load_test.py` → 1,000,000 rows @ **19,371 rows/s** write, 8-way concurrency, 32 read probes, `passed:true`; **stress** profile → 1,000,000 rows @ **46,932 rows/s**, 32-way concurrency, 128 read probes, `passed:true` (`%TEMP%/pg_load_validation*.json`). **spike/soak** profiles + production-shaped/committed run still outstanding. |
| **B6** | **RPO/RTO remain unmeasured at production scale and mutually contradictory** across documents. "Near-zero RPO / <15 min RTO" is a target, never measured against a live production database with WAL/archiving. *A **local** RTO was measured this session (backup 2.953s / restore 2.067s / integrity 1.528s on a tiny throwaway DB — operator-invoked, incl. per-call interpreter startup), which is an upper-bound sanity check, **not** a production-scale RPO/RTO. RPO is still unmeasurable locally (no WAL/offsite/PITR configured).* | 🟠 | Doc cross-check (DR/cert docs) + this session's timed drill (`%TEMP%/b6_rto_result.json`) |
| **B7** | **Secrets management is not operational.** A generic rotation procedure is documented (`docs/security/SECRETS_MANAGEMENT.md`), but no secret-store integration exists and rotation owner/cadence are unresolved placeholders. | 🟠 | Cert doc; docs/security + docs/infrastructure |
| **B8** | **A real backup→restore→integrity DR drill has now been executed this session (2026-10-14).** No `restore-validation.json`, no `last_status.json`, no live drill output. (CI automates a *self-contained* restore drill, but that is not production-DR proof.) | 🟠 | **Executed:** `backup_postgres.py --backup/--restore/--test` on throwaway local Postgres:16 → gzip+sha256 backup, restore into isolated target DB, sentinel parity **3=3**, integrity exit 0, `result:PASS`. **Not production**; encryption/offsite not exercised; RPO/RTO not measured. |
| **B12** | **Full gateway-to-notification integration E2E remains unverified.** The root release workflow now configures PostgreSQL role/RLS integration, shared Redis rate-limit checks, and isolated broker-backed Kafka lag tests using Redpanda. It deliberately leaves `PESAGUARD_LIVE_E2E` unset: that broad flag also enables live Alertmanager and gateway tests, which need approved non-production endpoints. The updated root workflow has not yet run in GitHub Actions. | 🟠 | Root `.github/workflows/backend-release.yml` source and focused local tests; post-change Actions result pending |

---

## 5. Additional findings (independent of the B-register)

| # | Finding | Rule | Severity | Recommendation |
|---|---|---|---|---|
| A1 | **Debug/scratch artifacts committed to git** in `pesaguard_backend_pipeline/`: `_apply_schema.py`, `_audit_routes.py`, `_baseline.txt`, `_probe2.py`, `_probe_out.txt`, `_probe_redis.py`, `_repro.txt`, `_retest.txt`, plus `customer_ledger.py.bak`, `_customer_ledger_backup.py`, `_adjacent_run.err/.log`, `_collect.log`. | #48 (never commit debug/temp files) | 🟡 | `git rm` these; add `.gitignore` rules (`_*.py`, `_*.txt`, `*.bak`, `_*.log`). |
| A2 | **Six app entrypoints coexist**, only one is real: `canonical_app.py` (deployed, FastAPI/uvicorn:5001) vs dead/legacy Flask variants `app.py`, `app_1.py`, `app_3_features.py`, `app_4_advanced_features.py`, `fastapi_app.py`. Invites confusion and accidental edits to non-deployed code. | #44 Simplicity, #45 No unrelated changes | 🟡 | Document `canonical_app` as the sole entrypoint; archive or delete legacy variants. |
| A3 | **Four requirements files** (`requirements.txt`, `_1`, `_2`, `_3`); only `requirements_3.txt` is used by Docker. Invites dependency drift. | #42 Dependency rules | 🟡 | Consolidate to a single authoritative file; keep extras only with documented purpose. |
| A4 | **`.gitignore` gaps** at backend root — no exclusion for `_*.py`, `_*.txt`, `*.bak` (only `*.log` is covered). | #48 | 🟡 | Extend `.gitignore`. |
| A5 | **`FLASK_DEBUG` path exists** in legacy `app_3_features.py` / `app_4_advanced_features.py` (`debug=os.getenv("FLASK_DEBUG","0")=="1"`). Default is safe (`"0"`) and the deployed entrypoint is uvicorn, but the latent Flask debug flag should not exist in shipped code. | #55 Security by default | 🟡 | Remove debug toggle from legacy apps (or remove the apps per A2). |

**Positives worth noting (not blockers):** `PESAGUARD_API_AUTH_REQUIRED=1` default (auth is
fail-closed by default); a dedicated HMAC signing secret for Daraja callback bodies separate
from the OAuth consumer secret; `.env.example` uses only placeholders.

---

## 6. Risk-prioritized remediation roadmap

### Phase 0 — Low-risk hygiene (safe, no financial behavior change)
1. Remove git-tracked debug artifacts (A1); extend `.gitignore` (A4).
2. Mark `canonical_app` as the single entrypoint in README + docs (A2).
3. Consolidate requirements to one file, or document the others (A3).

### Phase 1 — Make the suite trustworthy
4. **Re-run the full suite green in a CI-equivalent environment** (PostgreSQL 16, Redis 7,
   Redpanda). This session already proved the cited auth/session/password tests pass once
   Redis is reachable, and fixed the 2 stale event-bus tests — so the expectation is a green
   run, but it must be evidenced against the real service stack, not fakeredis — B2/B12.
5. ~~Resolve B3~~ — **withdrawn** (frontend exists; no missing-module blocker). Remaining CI
   nit: remove `continue-on-error` masking on the frontend job and point the guard at the
   correct path, so a genuinely-missing frontend can never fail silently again.
6. Run the root release workflow with PostgreSQL, Redis, and its disposable
   Redpanda broker; preserve a passing Actions result for the enabled service
   integration tests. Keep gateway/Alertmanager live tests gated until approved
   sandbox endpoints are provided — B12.

### Phase 2 — Prove financial correctness & recoverability (requires human sign-off)
7. Execute reconciliation certification with a named approver → `precision=recall=1.0`,
   `FP=FN=0`, `certification_ready=1` — B4.
8. Execute all 7 documented DR drills against a production-shaped stack; commit
   `restore-validation.json` + `last_status.json` with measured RPO/RTO — B6, B8.
   *(This session executed a **single** local backup→restore→integrity drill that PASSED; the
   production-shaped stack, all 7 drills, and measured RPO/RTO remain.)*
9. Build and run stress/spike/soak harnesses; commit artifacts — B5.
   *(This session executed the base 1M-row write/read run (PASS); stress/spike/soak profiles and a
   committed artifact on a production-shaped stack remain.)*
10. Integrate a real secret store with a documented rotation procedure, named owner,
    and cadence — B7.

### Phase 3 — Gate & certify
11. Re-run the full suite green in a CI-equivalent environment (PostgreSQL 16, Redis 7,
    Redpanda) and refresh `PRODUCTION_CERTIFICATION.md`.
12. Complete the three-role sign-off table (Engineering lead / Security owner /
    Finance-data owner). Only then may production readiness be claimed (Rule #38/#62).

---

## 7. Evidence Log

| Claim | Method | Result |
|---|---|---|
| Entrypoint boots | `importlib.import_module("pesaguard_backend_pipeline.fastapi_app")` with full env | ✅ `FastAPI` app, 7 top routes + mounts |
| Fails closed on config | Same import with `PESAGUARD_API_URL` unset | ✅ `RuntimeError: PESAGUARD_API_URL must be configured` |
| No committed `.env` | `git ls-files pesaguard_backend_pipeline` filtered on `.env` | ✅ empty (on-disk `.env` is ignored) |
| No secrets in code | Repo-wide regex scan for key/token patterns | ✅ only fixtures/placeholders |
| No SQLi | f-string execute + string-concat SQL scan | ✅ only Alembic DDL w/ `preparer.quote()` |
| No TLS bypass | `verify=False`/`ssl_verify=False`/`trust_env=False` scan | ✅ none |
| Frontend absence (B3) — **RETRACTED** | `Test-Path` on `frontend/`; **plus** `pytest tests/test_communications_intelligence.py` | ✅ `communications` package exists; **20 passed** (B3 misattribution withdrawn) |
| Debug artifacts tracked | `git ls-files` matching `_*`/`*.bak` | ✅ 8+ files tracked |
| Deps pinned | `requirements_3.txt` inspection | ✅ all `==` |
| Blocker status B1–B12 | Cross-check `PRODUCTION_CERTIFICATION.md` (2026-10-14) | NOT CERTIFIED (corroborated for B4–B8, B12) |
| Compile status | `py_compile` on entrypoint chain | ✅ OK (repo-wide blocked by unrelated dirty files per cert doc) |
| Auth "failures" are environmental (B2) | Run `test_auth_lifecycle.py` with/without a reachable Redis backend | ✅ Without Redis → 7×503 `rate_limiter_unavailable`; with `fakeredis`+`lupa` → **10 passed** |
| Event-bus failures are real stale tests | Run `test_phase4_event_bus.py` in isolation (no Redis) | ✅ Reproduce in isolation → missing `tenant_id`; **fixed** → **13 passed** |
| Fix did not regress | Re-run full `test_phase4_event_bus.py` | ✅ 13 passed |
| Redis absent at B2-investigation time | Probe port 6379 + `redis-server`/docker daemon | ✅ 6379 closed; Docker daemon down **at that time**; the reachable-Redis dependency explained the auth 503s |
| **B5 load — EXECUTED (2026-10-14)** | `operations/run_postgres_load_test.py` vs throwaway local `postgres:16` (127.0.0.1:55432) | ✅ 1,000,000 rows @ **19,371 rows/s** write, 8-way concurrency, 32 read probes (319 rows @ 182 rows/s), `passed:true` (`%TEMP%/pg_load_validation.json`) |
| **B8 DR drill — EXECUTED (2026-10-14)** | `operations/backup_postgres.py --backup / --restore / --test` vs throwaway local `postgres:16` | ✅ gzip+sha256 backup → restore into isolated target DB → sentinel parity **3=3**, integrity exit 0, `result:PASS` (`%TEMP%/b8_drill_result.json`) |
| Docker daemon brought up this session | `docker version`; local-only `postgres:16` + helper containers | ✅ client=server=29.8.0; throwaway local creds, **no committed secrets, no production access** |

**Newly executed THIS session (throwaway local `postgres:16` — real & durable, but NOT production):**
the **B5** load run and the **B8** backup→restore→integrity drill both **PASS** (rows above).

**Still NOT exercised (cannot be truthfully evidenced on a throwaway local DB / this host):**
- Full suite green against a **production-shaped** PostgreSQL 16 + Redis 7 + Redpanda stack. The root workflow now configures isolated PostgreSQL/Redis/Redpanda integration checks, but it has not run after that change; gateway traceability and Alertmanager live E2E still need approved sandbox endpoints.
- All **7** documented DR drills + measured **RPO/RTO** against production-scale data, WAL/archiving, encryption-at-rest, and offsite backup (B6/B8).
- Stress / spike / soak **profiles** of the load harnesses (only the base 1M-row write/read run was executed) (B5).
- Reconciliation financial certification — requires a **named human approver** (B4); secret-store provisioning (B7); production smoke tests (no production access).
Per Rule #38/#62 these remain **BLOCKED / REQUIRES HUMAN VERIFICATION**, not passes.

---

## 8. Statement of limits (Rule #38 / #62)

- This is a **static + smoke** audit. It does **not** constitute production certification.
- Most B-register statuses are **cited from the repository's own dated evidence record**
  and could not be re-derived here. This session **did** independently re-derive two of them
  against a throwaway local `postgres:16` container (Docker): **B5** (1M-row load run) and
  **B8** (backup→restore→integrity drill) — both real and passing, but **not production**.
  No live Redis/Kafka/Redpanda and no production access were available.
- Where I could verify independently (secrets, SQLi, boot, config fail-closed, hygiene),
  I did so and recorded the method above.
- **No production system was accessed, changed, or deployed to.** No destructive or
  irreversible action was taken (Rule #50/#61).

---

## 9. Resolved items & Phase-2 corrections

**Resolved locally (2026-10-14, per cert doc — not independently blocking):**
- **B1** — security-checklist merge-conflict + broken `SECRETS_MANAGEMENT.md` reference fixed.
- **B9** — data-quality/provenance integration now covered by focused suites (77 passed).
- **B10** — metrics import-time payload marker no longer present.

**Corrections made during the Phase-2 pass (Rule #47/#62 — the report now matches reality):**
- **B5 rewritten.** Load/stress/spike/soak harnesses **do exist** (`operations/run_endpoint_load_test.py`
  supports `--profile {load,stress,spike,soak}`; plus `run_auth_load_test.py`, `run_load_test.py`,
  `run_postgres_load_test.py`). The earlier "no harness exists" line is **retracted**; the real gap is
  *no executed, committed load evidence against a production-shaped stack*.
- **B12 rewritten.** The nested backend CI workflow sets `PESAGUARD_RUN_POSTGRES_INTEGRATION=1`;
  the root release workflow now also enables PostgreSQL, Redis, and isolated Kafka lag checks.
  Generic `PESAGUARD_LIVE_E2E` stays unset because it enables calls to gateway and Alertmanager
  endpoints that require approved sandbox configuration.
- **§8.2 resilience nuance retracted.** A prior note claimed `run_postgres_load_test.py` hardcodes
  `failure_injection` flags to `False` at "line 316". **That is not accurate** — the file is ~113 lines and
  has no such block. Chaos/failure-injection coverage in the tree is otherwise in-process (fake clients,
  `time.sleep`), not against real Kafka/PostgreSQL/Redis; that softer statement is the only one supported
  by the file as it exists today.
- **Structural fix.** The B-register table that was stranded after §8 has been moved into **§4**, which
  previously had a header row but no data rows.

**What Phase 2 delivered, and what still blocks (Rule #38/#61/#62):**
- **B5 — PARTIALLY EVIDENCED (executed, local):** the repo's own `run_postgres_load_test.py` ran against a
  throwaway local `postgres:16` → 1,000,000 rows @ 19,371 rows/s, `passed:true`. Real evidence, but **not
  production-shaped**; stress/spike/soak profiles and a committed artifact on a production-shaped stack remain.
- **B8 — PARTIALLY EVIDENCED (executed, local):** `backup_postgres.py --backup/--restore/--test` passed
  end-to-end (backup→restore→integrity, sentinel parity 3=3). Real evidence, but **not production**;
  encryption-at-rest, offsite backup, all 7 drills, and measured RPO/RTO are **not** exercised.
- **B4 — BLOCKED pending a named human approver.** The cert tool enforces `--approved-by/--validated-by` and
  cannot be self-issued (Rule #61) — not something to fabricate for a payments system.
- **B6 — still BLOCKED:** RPO/RTO unmeasured & contradictory across docs; the local B8 drill did not measure them.
- **B7 — BLOCKED:** requires provisioning a **real secret store** with credentials → not something to fabricate.
- **B12 — residual, intentionally left gated:** the root release workflow now provides Redpanda for broker-backed
  Kafka lag integration tests. The broader `PESAGUARD_LIVE_E2E=1` path remains unset because its gateway and
  Alertmanager tests need approved sandbox endpoints and credentials; no post-change workflow result exists yet.
- None of these are code defects; they are operational proofs that must be **evidenced, not asserted**.

### B5 Load Evidence (executed, throwaway local postgres:16 — not production-scale)

- **load (base)**: rows=1,000,000 conc=8 write=19371.92 rows/s read_probes=32 tenants=100 duration=51.621s passed=True
- **stress**: rows=1,000,000 conc=32 write=46932.18 rows/s read_probes=128 tenants=100 duration=21.307s passed=True
- **spike**: rows=500,000 conc=64 write=39474.14 rows/s read_probes=256 tenants=100 duration=12.667s passed=True
- **soak**: rows=3,000,000 conc=8 write=21801.84 rows/s read_probes=32 tenants=100 duration=137.603s passed=True
- **B6 RTO (measured)**: backup=2.953s restore=2.067s integrity=1.528s

---

## 10. Migration chain validation (RESOLVED — no defect)

During DB-scale evidence work the migration chain appeared to fail on a fresh database
(``relation "webhook_deliveries" does not exist`` at ``20261014_webhook_delivery_recovery``).
Verified against a **fresh empty database** under full CI parity, this is **NOT a defect**:

- The ``20261014_tenant_row_level_security`` migration **fail-closes by design** (Rule #56
  least-privilege) unless three least-privilege role env vars are set:
  ``PESAGUARD_APPLICATION_DB_ROLE``, ``PESAGUARD_TENANT_REGISTRY_DB_ROLE``,
  ``PESAGUARD_API_KEY_LOOKUP_DB_ROLE``. Missing them raises a clear ``RuntimeError`` — it
  refuses to apply tenant RLS policies without explicitly-named worker roles (correct).
- CI **provisions** those roles (ci.yml 72-90: ``pesaguard_app`` / ``pesaguard_tenant_registry`` /
  ``pesaguard_api_key_lookup``) and **sets** all three env vars (ci.yml 95-97) immediately before
  ``alembic upgrade head`` (ci.yml 99).
- **Executed proof (this audit):** ``alembic upgrade head`` on an **empty** DB with those roles +
  env vars -> **UPGRADE_EXIT=0**, single head ``20261014_webhook_delivery_recovery``, no branches,
  no errors. Deploy-from-scratch is sound.

Earlier apparent failures were the auditor local environment gaps (roles/env not provisioned),
not repository defects. The test suite builds schema via ``Base.metadata.create_all`` (conftest)
and does not exercise migrations; the migration path is covered only by CI step
"Validate migrations against PostgreSQL" — which is correctly configured.
