# Deployment Decision and Exact Fix Order

## Architecture decision

**No architecture is approved for live transactions from current evidence.** The
existing Compose design is the lowest-migration path for an isolated test deployment,
but it must not be exposed publicly in its current merged form. A single VPS can be
considered for a synthetic-data test only after its port, health, configuration, and
recovery checks pass. It remains a single point of failure and is not certified for
live financial traffic.

Do not remove Redis, Redpanda, workers, or monitoring to reduce cost without tracing
their data and retry responsibilities. Consider managed PostgreSQL/messaging only
after approved-region availability and real pricing are confirmed.

## Ordered remediation with acceptance criteria

1. **Select jurisdiction and provider.** Pilot/business owner confirms data,
   backup, and log region; legal review documents cross-border transfers.
   **Accept when:** provider, exact region, backup region, log region and tenant
   approval are recorded.
2. **Fix network exposure.** Remove or bind all non-ingress port publications;
   apply host/cloud firewall rules for only required ingress and administrative
   access. **Accept when:** merged Compose output shows no public database/cache/
   broker/metrics/API ports and an external port scan from an approved test host
   confirms only intended ports.
3. **Correct startup/health semantics.** Set the webhook check to its actual port;
   add database/broker/cache health dependencies or retry-safe startup; pin mutable
   image tags. **Accept when:** each health check matches its process and restart/
   dependency-failure tests pass.
4. **Repair test isolation.** Resolve sibling checkout import precedence and fixed
   temp database collision. **Accept when:** a clean CI-equivalent Python 3.12 run
   collects and completes with published counts and no hidden live flags.
5. **Provision deploy config securely.** Generate secrets, configure DB/Redis/
   broker URLs and trusted proxy count; validate Compose without displaying resolved
   credentials. **Accept when:** sanitized `config --quiet` succeeds and secrets
   are scoped/permissioned.
6. **Prove database migration safety.** Test an upgrade from a defined baseline
   against isolated PostgreSQL and validate schema/data; define rollback/forward-fix.
   **Accept when:** migration evidence and connection-budget calculation are signed.
7. **Prove backup recovery.** Configure encryption/off-site storage and retention;
   run an isolated restore and reconciliation integrity validation.
   **Accept when:** restore report records artifact, WAL point, duration, RPO/RTO,
   and passed integrity checks.
8. **Prove async correctness.** Exercise duplicate events, worker restarts, Redis/
   Kafka failure, retry/dead-letter, and idempotent replay with synthetic data.
   **Accept when:** each event has one durable financial result and no silent loss.
9. **Establish capacity.** Run representative synthetic benchmarks in the candidate
   VPS class; measure p50/p95/p99, throughput, CPU/RAM/disk, queue lag, DB pool and
   saturation. **Accept when:** approved SLO headroom and growth plan are met.
10. **Verify perimeter and external systems.** Confirm DNS/TLS, proxy IP chain,
    CORS origins, auth/key rotation, Daraja sandbox, email/SMS sandbox, alerts and
    monitoring privacy. **Accept when:** documented end-to-end smoke and failure
    drills pass without live payments.
11. **Prepare deployment/rollback.** Pin image digest, back up before migration,
    stage on isolated target, monitor, define rollback triggers and owner approval.
    **Accept when:** release workflow succeeds against staging and rollback is
    rehearsed.
12. **Authorize scope.** Business, security, operations, and data-residency owners
    approve the exact test/live scope before customer traffic.

## Deployment checklist (only after blockers)

- [ ] Approved provider, exact region, data-residency and DPA review.
- [ ] Firewall/security-group allowlists and no direct internal port publication.
- [ ] Host TLS and proxy settings reviewed; origin is not bypassable.
- [ ] Environment values provisioned with restrictive permissions; no secrets in logs.
- [ ] Compose interpolation validates; image digests/tags and resource ceilings pinned.
- [ ] Migrations reviewed and staged; backup completed and restore path tested.
- [ ] Database, Redis, broker, worker, API and webhook health validated.
- [ ] Synthetic idempotency, tenant-isolation, and reconciliation checks pass.
- [ ] Alerts, dashboards, queue lag, disk, backup status, TLS expiry and runbooks tested.
- [ ] Staging smoke test and rollback rehearsal complete.
- [ ] Explicit approval recorded before enabling real merchants or payments.

## Rollback

The intended release workflow promotes an image digest through Render staging and
production, but no completed deployment was verified. For a VPS, define a pinned
previous image and a tested schema compatibility/restore strategy before rollout.
Do not roll back application binaries across an incompatible migration without a
tested recovery plan.

