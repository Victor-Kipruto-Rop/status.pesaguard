# Production Gate Register

Rule applied: **every checklist item is either implemented and tested, or
explicitly documented as intentionally out of scope.**

Current: **761 tests, 0 failures, 0 skipped. 28 migrations, all applied
successfully against PostgreSQL 15.**

**Section E is resolved.** The structural blocker is gone. Verified with Docker
running: `DeveloperPlatformIntegrationTest` executes all 10 tests against
Testcontainers PostgreSQL, `skipped=0`, Flyway applies every migration, and
Hibernate `ddl-auto: validate` accepts the resulting schema. `ci.yml`'s
skip-assertion can now pass.

Update history: originally recorded at 607 tests / 16 migrations. V19 (developer
identity verification), V20 (account deletion workflow), V21 (environment
credential fingerprint uniqueness), V22 (JPA version columns), V23 (throttle
subject types) and V24 (usage project scope) were added since.

The distinction that determines pass/fail is no longer "does it exist" — it is
"if it does not exist, was that a decision or an omission." This register makes
every such decision explicit.

---

## A. Implemented and tested

| § | Area | Evidence |
|---|---|---|
| 3 | Organizations + membership | Invitations, lifecycle, history, audit |
| 4 | Projects | Full lifecycle, membership, settings |
| 6 | RBAC | 6 roles, permission registry, assignment, separation-of-duties enforcement |
| 9 | OAuth | Authorization code + PKCE, refresh rotation, replay detection, family revocation |
| 11 | Token management | Families, introspection, reuse detection |
| 12 | API scopes | Registry, versioning, restriction, deprecation, environment awareness |
| 13 | API catalog | Registry, versions, required scopes, OpenAPI metadata |
| 14 | API explorer security | SSRF guard — **44 tests** (private IP, localhost, cloud metadata, DNS rebinding, redirects) |
| 8 | Credential security | HMAC hashing, prefix, rotation, IP allowlist, last-used, lifecycle |

### Phase 1 — identity hardening (V28)

Opaque server-side access tokens plus rotating, hashed refresh-token families.
Opaque rather than JWT because revocation on logout, device removal and account
lockout must take effect immediately, and a stateless token cannot be revoked
without reintroducing the per-request database read this design exists to avoid.

| Item | Evidence |
|---|---|
| Refresh token families, hashed at rest | `user_refresh_tokens` stores SHA-256 only; `RefreshTokenServiceTest.theStoredTokenIsNeverTheOnePresented` |
| Single-use rotation | Conditional `claimForRotation` UPDATE; the loser of a race is treated as a replay (`aLostClaimIsTreatedAsAReplay`) |
| Replay kills the family, and survives the rollback | `ReplayedRefreshTokenHandler` is a separate bean using `REQUIRES_NEW`; revoking inline and then throwing would discard the revocation (`aReplayRevokesTheFamilyOutsideTheCallersRollback`) |
| Logout and session revocation end both credentials | `auth_sessions.refresh_family_id` pairing; `/logout`, `DELETE /auth/sessions/{id}`, and `POST /auth/sessions/revoke-others` revoke the affected refresh families |
| `/refresh` reissues an access token | Returns full `AuthenticationResponse`; a refresh that returned only a refresh token would leave the client unable to call anything |
| TOTP (RFC 6238) with single-use codes | `TotpTest` pins the RFC SHA-1 vectors; `MfaSecretRepository.consumeStepIfNewer` blocks concurrent replay of one step |
| TOTP secrets encrypted, not hashed | AES-GCM; `SecretEncryptionServiceTest` asserts tamper and cross-key rejection |
| Backup codes hashed, single-use | Conditional `consumeIfUnused` UPDATE; generated Base64URL codes match the request regex (`_` and `-` included) |
| Account-wide password reset | Reset token scoped to the user, not an organization; redemption revokes every session and refresh family across all organizations |
| Enumeration resistance | `/forgot-password` always answers 202 with no body |

**Phase 1 gaps that remain open, recorded rather than papered over:**

1. **Reset and verification tokens are not delivered.** `PasswordRecoveryService.request`
   returns the token and the controller discards it. There is no email wiring, so
   the reset flow cannot complete for a real user. The notification/outbox path
   exists (§26 above) but is not connected to these endpoints.
2. **Organization-wide MFA enforcement and enrollment step-up are incomplete.**
   A confirmed account factor is verified during password login, and removing an
   enabled factor requires the current password plus a fresh TOTP or recovery
   code. The organization `mfaRequired` policy is not enforced at login, and
   enrollment/confirmation do not yet require password reauthentication.
3. **Named tables differ from the specification.** The spec asks for
   `user_profiles`, `user_emails`, `user_sessions`, `user_devices`, `user_mfa` and
   `user_security_events`. The implementation uses the pre-existing `users` /
   `auth_sessions` convention, and `user_mfa_secrets` /
   `user_mfa_backup_codes` / `user_security_events` equivalents. Renaming is a
   migration with real blast radius across Phase 2+ and has not been done.

Neither is a silent omission: all three are unimplemented behaviour the identity
design calls for. All three should block a production-readiness claim for the
account-recovery, MFA and schema-conformance paths specifically.

### Access tokens are stateless RS256 JWTs

`AccessTokenService` signs with RS256; `RevokedTokenRegistry` is the Redis
denylist that restores immediate revocation on the paths that need it.

**What this costs, stated plainly:** a signed access token cannot be withdrawn,
so any revocation that does *not* go through the denylist takes up to one token
lifetime (5 minutes) to take effect. Every revocation path in the platform —
logout, session revocation, password change, password reset, organization
suspend/delete, membership suspension — denylists the affected token ids, so
immediate revocation is preserved and tested. What does *not* get immediate
revocation is a **role change**: `rol` is a claim, so an authority change takes
effect when the token expires rather than at once. That is inherent to the
chosen design and is bounded by the 5-minute TTL.

`rol` and `org` are claims despite the instruction not to put organization data
in JWTs, because `AccessDecisionService` and `TenantGuard` authorize on them and
a stateless verifier has no other source for the caller's identity. Documented in
`docs/token-lifecycle.md`.

## B. Implemented, NOT enforced at runtime

Built and unit-tested, but nothing invokes them. **These are omissions, not
scope decisions** — the checklist calls for the behaviour, and it does not happen.

| § | Item | Why it does not run |
|---|---|---|
| 5 | Sandbox/production isolation | No credential filter; a sandbox key is never actually refused against production |
| 7 | Multi-tenancy | Rules and composite FKs exist; **never executed** (no database) |
| 8 | Emergency/global revocation | Domain rules exist; no entry point |
| 18/19 | Usage + analytics | Ingestion runs; `api_key_id` always null, so credential analytics return nothing |
| 20/21 | Rate limits + quotas | **CORRECTION — now enforced.** `RateLimitFilter` is a live `@Component` at `HIGHEST_PRECEDENCE + 20` and returns real 429s with `X-RateLimit-*` headers, observed in the integration run. The Redis counter store is wired. **Quotas remain absent** (only the `QuotaPeriod` enum exists) |
| 24 | Security centre | Domain + persistence; no detectors, no controller |
| 25 | Security events | 8 event types defined; **no detector raises any** |
| 26 | Notifications | 18 events defined; nothing emits; no controller; email reports every send failed |
| 29 | Audit | Chain + trigger + catalog; **no call site uses the catalog**; operator actions unaudited |
| 30 | Internal admin | Separation built and tested; **no controller** |
| 38 | Request limits | **CORRECTION — enforced** via the same `RateLimitFilter` as §20 |

## C. Intentionally out of scope — DECLARED

These are deliberate. The checklist itself states the boundary, and it is
correct: **this backend manages developer-facing capabilities and does not
implement transaction, payment, fraud, or reconciliation business logic**, which
live in `pesaguard_backend_pipeline` and sibling services.

| § | Item | Reason |
|---|---|---|
| 1 | Business domain engines | Declared out of scope by the checklist; owned by the core platform |
| 12 | Transaction/fraud/reconciliation *logic* | Scopes are managed here; the engines are not |
| 30 | A general PesaGuard admin console | Explicitly "not the general PesaGuard administration platform" |

**Redis persistence (§1).** Declared: counters only, `appendonly no`, because
every value is recomputable and correct to lose on restart. A restart must not
hand a flooder a fresh budget mid-window.

## D. Missing — NOT out of scope, and not built

**These fail the gate.** They are inside the declared scope of this backend, and
they do not exist.

| § | Item | Status |
|---|---|---|
| 1, 17 | **Transactional outbox — NOW DONE (V25).** Domain, repository, relay with per-event transactions, exponential backoff + full jitter, DLQ with replay, 13 domain tests + 7 integration tests against real PostgreSQL. **Redpanda/Kafka itself still absent** — producer, consumer, groups, topics, retry topics, DLQ, replay | 0 references. Not a dependency, no container |
| 10 | **Service Accounts** | 0 references. Named pillar |
| 16 | **Webhook delivery** — queue, worker, timeout, DLQ, manual retry, replay | Zero outbound HTTP clients in the codebase |
| 17 | **Event emission** | Registry exists; nothing emits |
| 23 | **Developer Verification** | Only the production-access request workflow exists |
| 24 | Recent auth/activity views | No controller |
| 27/28 | SDK/CLI persistence + publishing | Domain + schema only; no repositories |
| 31 | Structured logs, tracing, alert wiring | **Logging + tracing now done.** Alerting rules not written |
| 32 | Redpanda health | Nothing to check |
| 33 | Circuit breakers | 0 — no resilience4j dependency, and **no `RestClient`/`WebClient`/`RestTemplate`/`HttpClient` anywhere in main**. There is no outbound HTTP client to protect yet, so this cannot be assessed until an integration exists |
| 35 | Contract tests, E2E, Redis/Redpanda integration | **Integration class now runs green.** Still no Redis or Redpanda container in the suite, and no contract/E2E tests |
| 36/37 | Load and failure testing | Requires infrastructure that has never run |
| 41 | Backups, PITR, restore test, RPO/RTO | **Scripts written, never executed** |
| 40 | CI pipeline execution | Pipeline complete, **never run** |
| 1 | Cursor pagination, email verification state | Absent — **email verification now DONE (V19)**; cursor pagination still absent |
| 2 | Account deletion workflow | Absent — **now DONE (V20)** |
| 9 | Environment credentials | **Now implemented** (Phase 4): keyed-HMAC fingerprint, cross-environment reuse rejection, revocation. Atomicity closed by the V21 unique index on `(project_id, fingerprint)`. **The derived query is now proven** — Spring Data parsed it at startup during the integration run. Still no HTTP entry point |

## E. Structural blocker — RESOLVED

**RESOLVED. The platform has now executed against PostgreSQL.** All 22 migrations
apply cleanly, and the 10 Testcontainers integration tests run for real:
`skipped=0`, `failures=0`.

Executing them for the first time exposed **nine latent defects that no unit test
could have caught**, because each existed only in the interaction between the
schema and the running application. Every one of them is a defect that would have
made the platform fail on first deploy:

| # | Defect | Effect in production |
|---|---|---|
| 1 | `application.yml` line 88: `operator-hmac-key` and the `security:` block jammed onto one line | **The application could not start at all.** YAML parse error |
| 2 | V7 `api_scopes`: 5 constraints stranded after the inserts; table truncated mid-`create` | **Migration failed.** Syntax error at "create" |
| 3 | V11 `event_subscriptions`: 3 constraints stranded after the trigger; table truncated | **Migration failed.** Syntax error |
| 4 | V12 `usage_buckets_endpoint_idx`: index truncated, continuation stranded at EOF | **Migration failed.** Syntax error |
| 5 | V11 seed: `developer.webhook.delivery.failed` had `action='delivery_failed'` | **Migration failed.** Violated `event_types_segments_match` |
| 6 | 17 columns declared `char(64)` in DDL, `varchar(64)` in entities | **Startup failed.** Hibernate schema validation rejected every hash column |
| 7 | `EnvironmentHistory` enum columns had no `@Enumerated` | **Startup failed.** Hibernate expected `smallint`, DDL had `varchar` |
| 8 | 4 tables lacked the `version` column their `@Version` field requires | **Startup failed.** Missing column — and optimistic locking would have been silently disabled |
| 9 | `login_throttles_type_check` allowed only `('account','ip')` | **Every invitation acceptance and every OAuth token call returned 409.** The feature had never worked |

Two further runtime defects surfaced immediately afterwards:

| # | Defect | Effect |
|---|---|---|
| 10 | No handler for `AuthorizationDeniedException` (Spring Security 6) | Every authorization denial returned **500**, polluting error metrics and alerting |
| 11 | `api_request_events.project_id` NOT NULL, but organization-scoped routes have no project | **Organization-level usage was never recorded.** Silently, in an async transaction |

Defect 9 is the most serious: invitation acceptance was broken in every deployed
environment, and no test had ever exercised it against a database.

Defects 10 and 11 were only discoverable by running. Both were silently degrading
security telemetry and billing accuracy while the API appeared healthy.

---

## Register summary

| | Count |
|---|---|
| Implemented and tested | 8 areas |
| Implemented, not enforced | 10 areas |
| Declared out of scope | 3 areas |
| **Missing (gate failures)** | **14 areas** (see section F for the per-layer view) |
| Blocked on infrastructure | **0** (resolved — see section E) |

---

## F. Build-order layer map

The platform is built in the following fixed order. This maps each layer to its
verified state, so "where are we" has one answer rather than an impression.
Evidence is source-level: presence of migrations, controllers and modules.

| # | Layer | State | Evidence |
|---|---|---|---|
| 01 | Foundation | done | config, common, health, Flyway, JSON logs, tracing |
| 02 | Identity & Authentication | done | users, auth_sessions, login_throttles, V19 email verification |
| 03 | Organizations | done | 37 classes, lifecycle + security settings + history |
| 04 | Projects & Environments | done | project 24, environment 24 classes |
| 05 | RBAC & Authorization | done | rbac 25 classes, roles/permissions/assignments |
| 06 | API Keys | done | api_keys, history, rotation, IP allowlist |
| 07 | OAuth & Service Accounts | **partial** | OAuth 32 classes (PKCE, refresh rotation, replay). **Service accounts: 0 references** |
| 08 | Token Security | done | token families, introspection, reuse detection, V15 revocations |
| 09 | API Scopes | done | api_scopes, restrictions, access decisions |
| 10 | API Catalog | domain+schema only | V18 catalog metadata; no repository/call site |
| 11 | API Explorer | **security only** | SSRF guard with 44 tests. No explorer feature, no controller |
| 12 | Sandbox | built, **not enforced** | sandboxes/limits/executions/isolation_guards; no credential filter |
| 13 | Webhooks | **absent** | 2 classes only (RetryPolicy, WebhookSignature). No entity, worker, DLQ, controller |
| 14 | Events & Subscriptions | registry only | V11 registry/subscriptions/deliveries; **nothing emits** |
| 15 | Usage & Analytics | built, **degraded** | ingestion runs but `api_key_id` always null |
| 16 | Rate Limits & Quotas | limits **enforced** (live filter, 429s observed); **quotas absent** |
| 17 | Production Access | done | production_access_requests + history |
| 18 | Security Center | persistence only | security_events; **no detector raises any** |
| 19 | Notifications | persistence only | 18 event types; **nothing emits**, no controller |
| 20 | SDK & CLI Registry | domain+schema only | V17 tables; no repositories |
| 21 | Audit | built, **uncalled** | chain + trigger; no call site uses the catalog |
| 22 | Internal Administration | separation only | platformadmin 5 classes; **no controller** |
| 23 | Observability | mostly done | Actuator + Prometheus + OTel bridge; alerting rules absent |
| 24 | Resilience | **absent** | no resilience4j, no outbound HTTP client |
| 25 | Performance & Scalability | unproven | requires running infra |
| 26 | Security Hardening | partial | headers, SSRF guard, throttling; never integration-tested |
| 27 | Docker & Kubernetes | partial | Dockerfile + compose + deployment/serviceaccount/networkpolicy. **Missing hpa, pdb, configmap** |
| 28 | CI/CD | complete, **never run** | ci.yml with skip-assertion, Trivy, SBOM, CodeQL, approval gate, rollback |
| 29 | Disaster Recovery | scripts only | backup/restore scripts **never executed** |
| 30 | Production Certification | **NOT MET** | blocked on the systemic item in section E |

### Ordering constraint discovered while auditing

Layers 13, 14 and 15 all depend on an event transport that does not exist.
Webhook delivery, event emission and high-volume usage ingestion each assume a
durable publish step. **The transactional outbox is the prerequisite for all
three**, and it is not itself a layer in this sequence.

Recommendation: treat the outbox as part of layer 13 (Webhooks) rather than as a
separate pillar, and build it first within that layer. The atomicity guarantee
lives in the outbox table, not in the broker, so it is provable against
PostgreSQL alone.
---

## G. Requested service catalog — disposition

A catalog of roughly 130 named "services" was supplied. It is treated as a
**capability list, not a deployment topology**, and deliberately not implemented
as ~130 separate services.

**Why not literal services.** The brief states this backend must not create
unnecessary microservices inside itself (§43, §89), and that it must not become
the whole PesaGuard platform (§2). Splitting a single Spring Boot application
into 130 deployables would add 130 network hops, 130 deployment units and 130
partial-failure modes to solve a problem that in-process modules already solve.
It would also make every cross-cutting concern — tenant context, audit, rate
limiting — a distributed transaction.

Most entries are already implemented as in-process modules or classes. The
gaps below are the genuine ones.

### G1. Present, under a different name

| Requested "service" | Actually implemented as |
|---|---|
| Authentication / Session / Session Security | `security.authentication`, `SessionService`, `AuthSession`, revocation tables (V15) |
| Authorization / RBAC / Permission | `rbac` (25 classes), `organization_roles`, `api_access_decisions` |
| OAuth 2.0 | `oauth` (32 classes), PKCE, refresh rotation, replay detection |
| API Key Management | `credentials` (14 classes) |
| Credential / Secrets Management | `EnvironmentCredentialService`, `CredentialCryptoService` (keyed HMAC) |
| Team & Membership / Invitation | `member`, `OrganizationMembershipService`, invitations + history |
| Project / Environment Management | `project` (24), `environment` (24) |
| API Catalog / Version / Discovery | V18 catalog metadata, `platform_api_versions` |
| API Explorer (security) | `explorer.security.OutboundTargetGuard` — 44 SSRF tests |
| Sandbox | `sandbox` (26 classes), V9 + V10 isolation constraints |
| Event Management / Subscription | `events` registry, V11 |
| Webhook Retry | `webhooks.delivery.RetryPolicy` (backoff + full jitter + DLQ classification) |
| Usage / Request Analytics | `analytics` (15 classes), V12 |
| Rate Limiting | `ratelimit` — **live filter**, Redis store, sliding window + token bucket |
| Production Access | `rbac.ProductionAccessController`, V14 |
| Security Center / Security Events | `securitycenter`, V15 |
| Notification | `notifications` (16 classes), V16 |
| SDK / CLI Management | `ecosystem` (11 classes), V17 |
| Audit Logging | `audit`, chained + append-only triggers |
| Internal Administration | `platformadmin` |
| Observability / Metrics / Tracing | Actuator, Prometheus, OTel bridge, structured JSON logs |
| Health / Readiness / Liveness | `health.HealthController` (`/health/live`, `/health/ready`) |
| DR / Backup & Restore | `infrastructure/disaster-recovery/` scripts |
| CI/CD, Deployment | `.github/workflows/ci.yml`, `infrastructure/kubernetes/` |
| Tenant Isolation / Multi-Tenant Context | `tenancy`, composite FKs, tenant-scoped repositories |

### G2. Genuinely absent — the real work

| Requested | Evidence | Blocks |
|---|---|---|
| **Redpanda/Kafka adapter** | `EventPublisher` port exists and is unimplemented; `OutboxRelay` degrades to leaving events PENDING rather than losing them | Layers 13, 14, 15 |
| **Redpanda/Kafka** | No dependency; no `KafkaTemplate`/`@KafkaListener` | Layers 13, 14, 15 |
| **Webhook Delivery / Replay** | 2 classes total; no entity, worker, queue, DLQ | Layer 13 |
| **Service Account Service** | 0 references | Layer 07 |
| **Idempotency Service** | Ad-hoc per-feature; no shared store | §35 |
| **Quotas** | Only the `QuotaPeriod` enum | Layer 16 |
| **Resilience / Circuit Breaker** | 0 references; no outbound HTTP client exists yet | Layer 24 |
| **Dead-Letter Queue** | Enforced in schema only; no consumer | Layers 13, 14 |
| **Feature Flag** | 0 references | Layer 20 |
| **Data Retention / Privacy** | `retention_policy` exists in the Python pipeline only | §48 |
| **Search / Indexing** | Absent | — |
| **Export / Import / File Management** | Absent | — |

### G3. Declared out of scope — belongs to another PesaGuard service

Per §2 and §57, this backend must not implement or directly query:

Fraud/Anomaly Detection, Billing/settlement, reconciliation engines, ledger,
transaction and payment business logic. Where the platform needs these it calls
an API or consumes an event; it never shares a database.

Note: the sibling repository `pesaguard_backend_pipeline` is a **single Python
Flask application** sharing one database — not the separately-owned services the
brief describes. Any integration therefore needs a service-identity decision
before code, not after.