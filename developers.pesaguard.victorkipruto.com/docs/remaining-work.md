# Remaining Work — Developer Platform

Verified against the current repository sources. Database migration and full
integration-suite execution still need confirmation in a PostgreSQL-enabled
environment.

---

## 0. The blocking item

The current migration chain has not been verified against PostgreSQL in this
work session. Constraints, foreign keys, triggers, seed rows, and Hibernate
mappings still require a real database migration and integration run.

This is not one task among many. Until a database runs, every other item below
is unproven rather than unfinished, and the ordering matters: fixing schema or
service code before validating the migrations risks building on a broken
foundation.

**First task: run `docker compose up` and execute the full suite with Docker
available.** Everything else is cheaper to do afterwards.

---

## 1. Systems with remaining gaps

### Webhook delivery
The dispatcher, bounded HTTP sender, durable event source, append-only attempts,
retry policy, dead-letter transition, replay API, and manual retry path exist.
PostgreSQL and outbound-delivery behavior still need runtime verification.

### Event emission (Phase 11)
Project, API-key, webhook-creation, and production-access events are emitted
transactionally and dispatched. Required-field validation uses registered
schemas; full JSON Schema validation and emitters for remaining event families
are still outstanding.

### Production access (Phase 14)
Request details, review transitions, active-grant enforcement for production API
keys, and grant-expiry processing are wired. Activation remains an authorized
explicit operation; automatic credential provisioning is still outstanding.

### Credential rotation + application suspension wiring (Phase 15)
Rules and permissions exist; the actions are not implemented.

### Security event detection (Phase 15)
Current implementation: unfamiliar-device sign-ins, refresh-token replay,
presentation of a non-active API key, API-key allowlist violations, and API-key
scope abuse raise tenant-scoped signals. API-key detections are de-duplicated
for 15 minutes per key and signal type. Repeated failures, suspicious webhook
activity, and abnormal-usage detection remain.

Two detectors currently raise signals: unfamiliar-device sign-ins and refresh
token replay. The notification path now alerts the affected user and active
security readers. API-key misuse, allowlist violations, scope abuse, repeated
failures, suspicious webhook activity, and abnormal-usage detection still need
detectors. Session device and IP fields are present and updated. The security
center now exposes tenant-scoped open/history reads and an audited resolution
operation with separate `security:read` and `security:control` permissions.

### Notification emission (Phase 16)
All notification types, the controller, and scheduled retry worker are wired.
API-key lifecycle, production-access transitions, recorded security signals,
exhausted webhook deliveries, and per-environment usage thresholds raise
notifications. Inbox pagination, read-state operations, preference payloads,
and channel/category enums are documented in OpenAPI.

### Email transport (Phase 16)
Notifications use the configured email provider, with Resend as the default.
Delivery failures are recorded for retry without logging recipient addresses or
message contents. Docker Compose includes a loopback-only Mailpit sink for
explicit local SMTP selection; deployments must provide a verified Resend sender
and API key before enabling public signup.

### SDK/CLI persistence + publishing (Phase 17)
Current implementation: SDK and CLI release metadata is persisted and available
from the public ecosystem registry. Operator publishing workflow and release
pipeline checksum attestation remain outstanding.

Domain and schema exist. **No repositories, no JPA entities, no controller, no
publishing workflow.** Checksums are entered by hand rather than written by the
release pipeline — the integrity guarantee currently depends on a human copying
64 hex characters correctly.

### Platform administration controllers (Phase 19)
Current implementation: health, billing, changelog, configuration, and incident
routes are available through the separate operator plane with capability checks.
Broader cross-tenant resource inspection and operator audit coverage remain.

The separation is built and tested. **No controller** — none of the eleven
operator capabilities is reachable.

---

## 2. Unwired code (exists, unreachable, or inert)

| Area | State |
|---|---|
| Rate limiting | Filter and API-key environment enforcement are wired with fail-closed behavior. Policy administration UI and broader policy persistence remain incomplete. |
| Usage analytics | Ingestion, API-key attribution, rollups, tenant-scoped query API, and frontend views exist. Retention and durable overflow handling remain. |
| Data exports | Permission-scoped CSV downloads cover usage, request logs, webhook delivery attempts, audit events, and organization identity. Bulk usage/log/delivery exports are paged and capped at 5,000 rows. |
| Security centre | Domain, persistence, session posture, and tenant-scoped event controller exist. Unfamiliar-device and refresh-token replay detectors notify users; broader detector coverage remains. |
| Audit catalog | `AuditAction` remains largely unused; services still pass hand-written action strings. Verify project-scoped audit coverage per service. |
| Notification preferences | Tenant-authenticated preference read/update endpoints and frontend controls exist. |

---

## 3. Unverified-by-construction

- **Redis rate limiting** — Lua scripts compile but **have never executed**.
- **Current Flyway migration chain** — not applied and verified against a real
  PostgreSQL database in this work session.
- **Audit chain v1/v2/v3** — three canonical forms; v1 and v2 have never verified against a stored row.
- **`@ConditionalOnProperty` store selection** — both stores exist; only the in-memory path is exercised in tests.
- **Second filter chain (Phase 19)** — no test drives a developer session at `/internal/**` through the real chain.

---

## 4. Correctness gaps worth fixing before scale

- **Rate limiter fails closed globally.** A Redis outage rejects limited traffic
  with 503 until the counter store is restored; confirm this availability tradeoff
  against production recovery objectives.
- **Usage events can overflow their bounded recorder.** The dropped count is
  exposed for monitoring, but there is no durable overflow queue.
- **Audit chain verification is never scheduled.** `verifyChain` exists but
  nothing calls it, so tampering is noticed only when someone looks.
- **Operator actions are not audited.** Phase 18 needs an `actorUserId` an
  operator does not have. An operator revoking a customer's credential is exactly
  what the audit log exists to capture.
- **V8 and V13 are missing from the migration sequence.** Adding them later
  needs out-of-order handling or renumbering.
- **98 `saveAndFlush` call sites.** Only the audit one was analysed and changed.
  Each forces a round trip inside a transaction; the rest are unreviewed.

---

## 5. Untested because unverifiable here

Query plans, index usage, connection utilization, locking, transaction duration
under load, webhook delivery throughput, queue depth, consumer lag, event
throughput, and DLQ behaviour. All require infrastructure that has never run.
`docs/reliability-and-performance.md` has the dependency-ordered plan.

---

## 5. Completed in this pass

- **Feature flags and maintenance mode.** Deployment-configurable runtime controls expose a public status document, hide disabled dashboard navigation, return structured 404s for disabled feature APIs, and reject unsafe mutations during maintenance. See `docs/runtime-controls.md`.
- **Frontend network resilience.** Browser offline state is visible throughout the portal, and transport failures now preserve the fact that no API response was received instead of surfacing browser-specific fetch errors.
- **Form draft recovery.** The non-sensitive project-name field is recovered from browser storage after an interruption and cleared only when creation succeeds. Passwords, tokens, secrets, and MFA data are excluded.
- **Near-real-time inbox state.** The portal refreshes unread notifications every 30 seconds while visible and immediately when the browser tab is revisited, without polling inactive tabs.
- **Concurrent membership changes.** Organization and project memberships now have database version columns and state-changing membership paths obtain row locks before changing authorization. Optimistic-lock conflicts remain structured `409 CONCURRENT_UPDATE` responses.
- **Status and incidents.** Public-safe platform status now includes component state, maintenance, and public incidents. The internal operator plane can create, update, resolve, and add public/private incident updates with capability checks and a required operator reason.
- **Project restoration.** The existing server-side archive/restore lifecycle is now represented in the project directory with separate confirmation dialogs for both transitions.

## 6. Not started

- **OpenAPI coverage.** Usage analytics, data exports, notification
  inbox/preferences, security-event management, and the production-access
  lifecycle now have route entries. The remaining API surface still needs a
  controller-by-controller coverage audit.
- **Retention policies.** `audit_events`, `api_request_events`, and
  `security_events` grow without bound. Recompute-based rollups make raw-event
  pruning unsafe until the coarsest window closes.
- **CI.** Never run. The working tree is untracked.
- **Observability.** No metrics, no alerts. `droppedCount()` on the usage
  recorder, chain-verification failure, and notification exhaustion are all
  observable only by reading logs.

---

## Suggested order

1. **Run the database.** Execute all migrations; run the suite with Docker. Every
   other item is unproven until this passes.
2. **Fix what the run breaks.** Expect real failures — this is the first
   execution, not a regression.
3. **Webhook delivery + event emission.** The two largest absent subsystems, and
   both are prerequisites for the platform being useful.
4. **Wire the inert code**: rate-limit filter, usage credential attribution,
   audit catalog adoption, notification emission + retry worker.
5. **Phase 14 request fields + provisioning + enforcement.**
6. **Phase 19 controllers + operator audit.**
7. **Persistence for security centre, notifications, SDK/CLI.**
8. **Then and only then: load, plan, and scale testing.**

Steps 3+ can be parallelised across people. Step 1 cannot, and everything after
it is cheaper.
