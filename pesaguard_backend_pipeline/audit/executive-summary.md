# Executive Summary

**Service/version:** PesaGuard Core API; repository `main` at
`8c50cad38e5176aec2513a6ef4584493364a1d42` (dirty worktree; not a release artifact).
**Environment assessed:** Windows developer checkout, Python 3.13.15; no services
started, no production credentials inspected, and no target VPS or live dependencies
available.

## Verdict

**NOT READY FOR VPS DEPLOYMENT.** The repository contains a substantial Docker
Compose deployment and operational code, but its effective production exposure is
unsafe as currently merged, configuration cannot be validated with available
deployment settings, the complete test suite did not collect, and recovery,
capacity, DNS/TLS, and external integrations have not been verified in a target-like
environment. This is not a statement that every application function is broken; it
is a statement that safe deployment has not been established.

## Highest-priority blockers

1. **Published internal ports (HIGH):** resolving the base Compose file with the AWS
   overlay shows API, PostgreSQL, Redis, Redpanda, Prometheus, Alertmanager, Grafana,
   and OTEL ports still published on all host interfaces. The overlay's `ports: []`
   does not clear the base mappings. Only the added Nginx port binds `127.0.0.1`.
2. **Test suite blocked (HIGH):** the requested full pytest run stopped in collection
   with 115 collection errors. Test modules resolved to the sibling
   `_backend-status-deploy` checkout, not the checkout under audit; no test execution
   or pass/fail/skip counts were produced.
3. **Recovery not proven (HIGH):** production backup code requires encryption and
   off-site upload commands, but target configuration, backup execution, retained
   artifacts, and an isolated restore drill were not available. The Compose WAL
   archive is on a named volume on the same host.
4. **Region and jurisdiction unresolved (HIGH):** repository documentation says the
   provider and exact region await real-world confirmation. `ke-1` is a logical
   label, not proof that a provider stores data in Kenya.
5. **Single-host capacity and resilience unproven (HIGH):** the architecture includes
   Postgres, Redis, Redpanda, multiple APIs/workers, and observability on one host;
   there is no representative benchmark or failover evidence. A VPS remains a
   single point of failure.

## Current architecture

FastAPI/Uvicorn on port 5001 mounts the Flask dashboard API; a separate Flask
webhook process defaults to port 5000. Nginx routes webhook paths to the webhook
receiver and other traffic to the dashboard API. PostgreSQL stores application
state; Redpanda supports event processing; Redis backs RQ jobs; reconciliation and
communications workers perform asynchronous work. Prometheus, Alertmanager, Grafana,
and OTEL provide configured observability components. DNS, public TLS, service
startup, cross-service readiness, and external provider behavior are unverified.

## VPS recommendation

The existing EC2 guide specifies **2 vCPU, 8 GiB RAM, and at least 50 GiB gp3**
as a starting size for the complete Compose stack. Treat that as a repository
recommendation, not a benchmark or production certification. For an isolated test,
use at least the documented baseline, keep all internal ports private, and monitor
resource saturation. No minimum for live transaction volume can be justified until
representative benchmarks and recovery objectives exist. A larger VPS does not
remove the single-host failure domain.

## Cost

**No defensible monthly USD or KSh total can be stated yet.** Provider, exact region,
storage growth, bandwidth, backup destination, managed-service choice, and FX
assumption are unresolved. Official pricing pages reached during this audit did
not provide a comparable selected plan quote. This is a blocked estimate, not a
zero-cost estimate. See [cost analysis](cost-estimate.md).

## Required remediation sequence

1. Select the provider and data region with the pilot/customer and document residency.
2. Correct and revalidate Compose port exposure; add a firewall allowlist.
3. Fix the webhook health-check port and make dependency readiness/startup behavior
   explicit.
4. Isolate test paths from the sibling checkout, use a fresh test database path, and
   establish a clean test run on the CI-supported Python version.
5. Provision and validate secrets/configuration without printing resolved values.
6. Configure encrypted off-site backups and complete an isolated restore drill.
7. Add target-like integration and capacity tests with synthetic data; document SLO,
   RPO, RTO, and rollback criteria.
8. Verify public DNS, TLS, trusted proxy/client-IP behavior, and sandbox integrations
   before any transaction test.

## Readiness gates

| Gate | Status | Reason |
|---|---|---|
| Scope and architecture | GAP | Multiple entry points and deployment documents disagree on API hostname; region/ownership decisions remain open. |
| Correctness and data integrity | BLOCKED | Schema constraints and one migration head are source evidence; tests could not collect and no production-like integration run occurred. |
| Security and tenant isolation | GAP | Strong source-level controls exist, but direct host port exposure and live cross-tenant verification are unresolved. |
| Reliability and recovery | GAP | Backup safeguards exist in code; off-site execution, restore drill, restart behavior, and HA are not verified. |
| Performance and capacity | BLOCKED | No reproducible benchmark, workload profile, SLO, or saturation measurements. |
| Observability and operations | GAP | Health and monitoring configuration exists; alerts, dashboards, persistence, and operational delivery are not verified. |
| Release controls | BLOCKED | Workflow is configured, but no successful CI/deployment run was established in this audit. |
| External systems | BLOCKED | Production database, messaging, payment, email/SMS, DNS, and cloud integrations were not exercised. |

