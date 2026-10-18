# Production Gate Audit

Against the 42-section PesaGuard Developer Platform checklist.
**607 tests, 0 failures, 10 skipped. 16 migrations, none applied.**

Gate rule: every item must be *implemented and tested*, or *explicitly out of
scope*. Verdict key: **MET** / **PARTIAL** (built, unwired or unverified) /
**ABSENT** (does not exist) / **GATE FAIL** (claimed or implied but not evidenced).

---

## The gate result

**The gate is not met.** The platform is roughly 55% built as foundations, and a
large number of items are neither implemented nor documented as out of scope.

| Verdict | Sections |
|---|---|
| MET and tested | 3, 4, 6 (core), 9, 12, 13, 14 (security) |
| PARTIAL — built, unwired | 1 (partial), 7, 8, 18, 19, 20, 21, 24, 26, 29, 30, 39, 40 |
| ABSENT | 10, 16 (delivery), 17 (emission), 23, 27 (persistence), 28 (persistence) |
| GATE FAIL | **5, 25, 31, 32, 35, 36, 37, 38, 41, 42** |

---

## GATE FAIL — must be resolved before production

**§5 Environment isolation.** Sandbox/production isolation rules exist in
schema and services, but **nothing enforces them at request time**. No credential
filter exists, so a sandbox credential is never actually refused against
production. The rules are written; they are not running.

**§25 Security event system.** All eight detectors are *defined*. **None runs.**
There is no severity, no classification, no investigation workflow. This is the
largest single gap in governance.

**§31 Observability.** Logs are **not structured JSON** (0 hits for any JSON
layout). Tracing is **absent** — no OpenTelemetry, no trace or span IDs, no
cross-service correlation. Metrics scraping exists (Prometheus, added Phase 22).
Roughly one third of this section exists.

**§32 Health & readiness.** `/health/live` and `/health/ready` exist.
Redis health is checked by the rate-limit store. **Redpanda health has nothing to
check** — no broker. Dependency health is partial.

**§35 Testing.** No **contract tests**. No **end-to-end tests** beyond one
integration class. Integration coverage is a single class that has never run.
Security testing is genuinely strong (SSRF, replay, isolation, privilege
separation) — that is the best-covered area.

**§36 Load & stress.** Not performed. Requires infrastructure that has never run.

**§37 Failure testing.** Not performed. No evidence of graceful degradation under
Redis or PostgreSQL loss.

**§38 Security hardening.** SAST (CodeQL), dependency scan, secret scan,
container scan, SBOM, secure headers, strict CORS all exist in CI.
**Request limits do not** — the limiter is unwired. TLS is not configured in the
application (assumed at the ingress). Production debug: not verified.

**§41 Disaster recovery.** **No backups configured. No PITR. No restore test. No
RPO or RTO defined.** There is currently no evidence the platform can be restored
at all. This is the highest-severity gap in the entire checklist.

**§42 Production readiness.** Fails on: migrations untested, no tested backups,
no monitoring alerting, cross-tenant access unverified at runtime (all tenant
isolation tests are unit tests against an unexecuted schema).

---

## ABSENT — does not exist

| Item | Evidence |
|---|---|
| **§10 Service Accounts** | 0 references. A named pillar of the architecture |
| **§16 Webhook delivery** | Queue, worker, HTTP client, DLQ producer, manual retry, replay — none exist. There are **zero outbound HTTP clients** in the codebase |
| **§17 Event emission** | Registry exists; nothing emits, no broker, no consumer groups, no retry topics, no DLQ |
| **§23 Developer Verification** | Only the production-access *request* workflow. No organization/developer/application verification, no evidence or re-verification workflow |
| **§27/28 SDK & CLI persistence** | Domain and schema only. No repositories, no publishing. Checksums entered by hand |
| **Redpanda** | 0 references. Drawn in the target architecture as if present |
| **Circuit breakers** | 0 — no resilience4j |
| **Cursor pagination** | 0 — offset-based `PageRequest` only |
| **Email verification state** | 0 references |
| **Metrics: metering** | `EnvironmentLimits` javadoc states it explicitly: "does not yet meter traffic" |

---

## PARTIAL — built but not enforced or not evidenced

- **§1 Foundation** — solid (stateless, Flyway, Hikari, graceful shutdown, JVM
  tuning). Virtual threads: not adopted. Production config separation: partial.
- **§7 Multi-tenancy** — `TenantGuard`, tenant-scoped repositories, composite
  FKs. **Unverified at runtime** because no test has executed against a schema.
- **§8 API keys** — full lifecycle, HMAC hashing, rotation, IP allowlist.
  **Emergency/global revocation** not implemented. No credential filter, so keys
  are never presented to the authenticator.
- **§18/19 Usage & analytics** — ingestion and rollup work. `api_key_id` is
  **always null** because no credential filter exists, so credential-usage
  analytics returns nothing.
- **§20/21 Rate limits & quotas** — Redis-backed, tested algorithms. **No filter
  calls them. Nothing is limited.** Quota alerts (80/90/100%) not implemented.
- **§24 Security Centre** — domain and persistence; no detectors, no controller.
- **§26 Notifications** — 18 events defined; **nothing emits**, no controller, no
  retry worker, and email transport **reports every send as failed** (deliberate).
- **§29 Audit** — hash chain, append-only trigger, declared catalog. **No call
  site uses the catalog**; no call site supplies `projectId`; operator actions are
  unaudited. Before/after metadata: partial.
- **§30 Internal admin** — separation built and tested. **No controller**; none
  of the operator capabilities is reachable.
- **§39 Container/K8s** — manifests complete and YAML-validated. **ServiceAccount
  and minimal RBAC not written.** Secret management not set up.
- **§40 CI/CD** — full pipeline present. **Never executed** — no CI run has
  happened. Deployment audit absent.

---

## What would move the gate

1. **Run the database.** Execute migrations, run the suite. Without this, §5, §7
   and §42 cannot be evidenced at all, and every "unit-tested" isolation claim
   remains unproven.
2. **Service accounts (§10).** A named pillar that does not exist.
3. **Webhook delivery + event emission (§16, §17).** Two entire pillars.
4. **DR (§41).** Backups, PITR, one rehearsed restore, defined RPO/RTO.
5. **Security detectors (§25) and request enforcement (§5).**
6. **Observability (§31): structured JSON logs and OpenTelemetry tracing.**
7. **Execute CI once.** An unrun pipeline is an unverified pipeline.