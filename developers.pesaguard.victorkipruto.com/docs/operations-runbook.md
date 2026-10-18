# Operations Runbook — Production & Enterprise Readiness (Phase 22)

Status: **deployment pipeline and manifests are implemented, but a successful
deployment against protected staging/production environments has not been
verified from this repository workspace. Backup/restore, email delivery, and
live payment-provider checks remain deployment-owner responsibilities until
their environment credentials and test resources are configured.**

## Deployment

`infrastructure/kubernetes/deployment.yaml`, `networkpolicy.yaml`, and
`serviceaccount.yaml` are applied by CI. CI builds and scans one container,
publishes it to GHCR on pushes, and deploys the resulting immutable digest to
staging and production. Pull requests build and scan without publishing.

Configure protected GitHub Actions environments named `staging` and
`production`. Each environment needs:

- Secret `KUBECONFIG` with narrowly scoped access to its namespace.
- Secret `GHCR_PULL_TOKEN` and variable `GHCR_PULL_USERNAME` for the cluster's
  image-pull secret.
- Variable `DEPLOYMENT_URL`, set to the HTTPS public API URL.
- Variable `PROMETHEUS_URL`, set to the HTTPS Prometheus API URL.
- Variable `PESAGUARD_UP_QUERY`, a PromQL selector that identifies this
  environment's platform target (for example, a selector including the
  deployment's scrape job and namespace labels).
- Optional secret `PROMETHEUS_BEARER_TOKEN` if Prometheus requires bearer
  authentication.

Production must have required reviewers enabled in GitHub environment
protection. CI fails before deployment if required values are missing. No
credentials or cluster access are committed to this repository.

**Probes are split on purpose.** Liveness hits `/health/live` and does *not* check
PostgreSQL or Redis. Readiness hits `/health/ready` and does. If liveness
depended on the database, a database outage would restart every pod and turn a
degradation into a full outage.

`startupProbe` is generous (30 x 5s) because Flyway runs at startup and a slow
first migration must not be killed as a hang. It and readiness use the private
management port and include PostgreSQL and Redis health. Liveness uses the
public application port and does not depend on a datastore.

**Resources:** requests 500m/768Mi, limits 2000m/1536Mi. Deliberately unequal —
memory is not compressible, and a limit equal to the request risks an OOMKill on
a GC spike.

**Graceful shutdown:** `terminationGracePeriodSeconds: 45`, a 10s `preStop` sleep
so endpoint propagation removes the pod before it stops accepting, and
`maxUnavailable: 0` so a rollout never dips below the PDB minimum.

**PDB:** `minAvailable: 2` of 3 replicas. `minAvailable: 1` would permit a
two-pod simultaneous loss.

**HPA:** 3-20 replicas at 70% CPU. Scales up fast (30s window, +100%/30s) and
down slowly (600s window, -25%/60s) — thrashing removes capacity exactly when
traffic returns.

## Network isolation

Default-deny first, then explicit allows. Order matters: a namespace with only
allow rules still permits everything not named.

This is what makes the Phase 19 separation more than an application convention.
`/internal/**` is reachable only from the admin namespace, and the public
developer Ingress targets 8080 only. Actuator metrics/readiness listen on 9090,
allowed only from the monitoring namespace. Egress allows DNS, PostgreSQL,
Redis, and public HTTPS/SMTP while excluding private/reserved IP ranges from the
external rule. Email delivery, provider APIs, and public webhook targets still
need environment-level tests.

## Developer-to-Core service JWT migration

The Java API-key synchronizer supports an explicit, opt-in migration setting:
`PESAGUARD_PIPELINE_SERVICE_JWT_MODE`. It defaults to `HMAC_ONLY`; supported
values are `HMAC_ONLY`, `DUAL_REQUIRED`, and `JWT_PRIMARY`. No production rollout
is implied by this code or runbook entry.

Before enabling a JWT mode, store an RSA PKCS#8 private key (`BEGIN PRIVATE KEY`)
in the deployment secret store as `PESAGUARD_PIPELINE_SERVICE_JWT_PRIVATE_KEY_PEM`
and set `PESAGUARD_PIPELINE_SERVICE_JWT_ACTIVE_KID` to a stable key ID. Do not
put private key material in source, images, or config maps. Keys must be RSA 2048
bits or stronger. `PESAGUARD_PIPELINE_SERVICE_JWT_TTL_SECONDS` defaults to 120
and accepts 60-300 seconds. During rotation, configure prior verification keys
as PEM `BEGIN PUBLIC KEY` values under
`pesaguard.pipeline.service-jwt.overlap-public-keys.<kid>`; only public keys are
returned by JWKS. Retain old public keys until all tokens signed by their private
key have expired and verifier caches have been refreshed.

Core can retrieve active and overlap public keys from
`GET /internal/v1/service-jwt/jwks.json`. The route is unauthenticated at the
application layer because it contains public keys only; keep it on the internal
service network. The Java sync request uses `Authorization: Bearer <JWT>`.
`DUAL_REQUIRED` sends this token and the existing timestamp/body HMAC headers.
`JWT_PRIMARY` always requires the JWT and sends the existing HMAC headers too
when a valid HMAC secret is configured; HMAC-only requests remain available to
the verifier as an explicitly controlled migration fallback.

The Python verifier contract is: pin `RS256`; resolve `kid` from this JWKS;
require `iss=developer-platform`, `aud=core-api`,
`sub=svc-developer-platform`, `iat`, `exp`, a unique/replay-protected `jti`,
and `scope` as a JSON array containing exactly `service:sync` for this sync
operation. Enforce the 60-300 second lifetime and reasonable clock skew. During
`DUAL_REQUIRED`, require both JWT and the existing HMAC over the unchanged
timestamp, method, path, and exact body bytes. During `JWT_PRIMARY`, prefer a
valid JWT; accept HMAC-only fallback only under an explicit verifier rollout
policy, and reject an invalid presented JWT rather than silently downgrading.
Deploy and verify Python-side JWKS retrieval, claim checks, replay protection,
and fallback policy before changing the Java setting from its default.

## CI/CD

CI runs backend tests and database-backed migration checks, frontend typecheck
and build, dependency/security scans, and CodeQL. On pushes it publishes and
scans a SHA-256-addressed container image, retains its SBOM, deploys the same
digest to staging, checks protected-route behavior, verifies readiness reports
PostgreSQL and Redis as `UP`, confirms JVM metrics are exposed, and confirms
Prometheus reports the configured target as `UP`. Production uses that identical
digest and the separately protected `production` environment; configure required
reviewers in GitHub environment settings.

Three steps are worth calling out:

- **The suite asserts database tests actually ran.** If anything is skipped, CI
  fails rather than reporting green. The only allowed skip is the explicitly
  opt-in pipeline synchronization smoke test when its integration target is not
  configured. A silent database-test skip is how "migrations never executed"
  survives for months.
- **Staging smoke tests assert a 401/403** from an unauthenticated protected
  route. A **200 there means authorization is not enforced**, so the test fails on
  a security improvement — deliberately.
- **Production requires environment approval** and rolls back automatically if
  verification fails. A failed verification is not a reason to leave the bad
  revision serving traffic.

## Monitoring

`/actuator/prometheus` is bound to the private management port 9090. The public
application port does not expose that listener. NetworkPolicy permits port 9090
only from the monitoring namespace; CI verifies the endpoint and queries
Prometheus for an `UP` target after deployment. This confirms scrape only when
the protected environment contains a valid Prometheus URL and target selector.

| Signal | Alert condition |
|---|---|
| API availability | Any pod down > 2 min |
| API latency | p95 > 500 ms for 5 min |
| API errors | 5xx rate > 1% for 5 min |
| Auth failures | 401/403 spike (brute force) |
| Authorization failures | Any cross-tenant hit — **page** |
| Credential operations | Any revoke — **page** |
| Usage ingestion | `droppedCount()` > 0 for 5 min — **usage is incomplete** |
| Rate-limit operations | Fail-open (Redis down) — **page** |
| DB health | Hikari pending > 0 sustained = pool exhaustion |
| Redis health | `isAvailable()` false — **page** |
| JVM health | Heap > 85% sustained |

Webhook success/failure/latency, event lag, and DLQ size are requested but have
**no source**: the webhook delivery system and event emission were never built
(see `remaining-work.md`). Those metrics cannot exist until the subsystems do,
and a dashboard of permanently-zero series would be worse than none — it looks
healthy.

## Disaster recovery

| Data | Strategy | Status |
|---|---|---|
| PostgreSQL | Base backup daily + WAL archiving for PITR | **Not configured or verified in this repository** |
| Restore test | Restore to scratch monthly, verify row counts | **Not done; requires the database operator and a scratch target** |
| Redis | No persistence by design; counters are recomputable | Implemented |
| Redpanda | Retention + tiered storage | **N/A — no broker** |
| Event replay | Re-emit from `event_deliveries` | **N/A — nothing emits** |
| Configuration | Secret manager, values versioned | **Not set up** |

### Restore testing is the item that matters

A backup that has never been restored is a hypothesis, not a backup. The procedure
that must exist before production:

1. Provision a scratch PostgreSQL from the base backup.
2. Replay WAL to a named point in time (`recovery_target_time`).
3. Verify row counts per table and re-run `verifyChain` on the audit table — it
   is the one table where silent corruption is invisible to a row count.
4. Record the measured RTO.

This workspace has no target database credentials, backup store, or scratch
restore target, so it cannot verify this procedure. Until an operator records a
successful restore test and measured RTO, **there is no evidence the platform can
be restored**, and this remains the largest operational risk.

### Redis recovery, specifically

Redis holds only rate-limit counters and is configured `appendonly no` on
purpose: every value is recomputable, and persisting them would cost I/O on the
hot path to protect state that is *correct to lose* on restart. A restart must not
hand a flooder a fresh budget mid-window; the consequence is bounded to one
window of excess traffic, which is the right trade against the alternative.

## Known operational gaps

- **No alerting is wired.** The table specifies what should alert; nothing does.
  Prometheus rules and Alertmanager config are not written.
- **No dashboards.**
- **No DR has been rehearsed.** The largest risk here.
- **No live SMTP delivery check has been run.** Configure a staging-only
  recipient/canary and verify delivery through the deployment mail provider;
  do not send test messages to real users.
- **No live payment-provider callback check has been run.** Use provider sandbox
  accounts and signed test callbacks in staging before enabling live credentials.
  Production transaction processing and transaction-event webhook ingestion are
  documented as unimplemented; do not use the presence of API/UI surfaces as
  evidence that either is production-ready.
- **Rate-limit storage failure rejects traffic with 503.** Monitor
  `RATE_LIMITER_UNAVAILABLE` responses and restore Redis promptly; do not disable
  enforcement as an outage workaround.
- **Log aggregation is unspecified.**
