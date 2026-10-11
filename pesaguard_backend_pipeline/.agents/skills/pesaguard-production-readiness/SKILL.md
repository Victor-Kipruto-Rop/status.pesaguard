---
name: pesaguard-production-readiness
description: "Assesses or prepares a PesaGuard service for production release. Use when asked whether a backend service is production ready, to close release gaps, verify security and tenant isolation, prove reliability and recovery, validate monitoring, or prepare a deployment."
---

# PesaGuard Production Readiness

Use this skill for release-readiness decisions. It is an evidence gate, not a claim that a service is production-ready because it has tests or has been implemented.

## Readiness gates

Assess each gate as **PASS**, **GAP**, **BLOCKED**, or **NOT APPLICABLE**, with evidence and an owner/action for every gap:

1. **Scope and architecture:** The service has an approved product scope, a documented contract, explicit ownership, bounded dependencies, and no duplicate authority for the same data or policy.
2. **Correctness and data integrity:** Integration tests cover success, invalid input, failure, retries, idempotency, concurrency, and recovery. Database constraints and migrations preserve required invariants.
3. **Security and tenant isolation:** Authorization is enforced server-side at every driver port. Cross-tenant access tests cover reads and writes, including asynchronous and cached paths. Secrets and PII are protected in storage, transit, responses, logs, traces, and metrics. Input limits, timeouts, rate limits, and dependency controls are verified.
4. **Reliability and recovery:** Dependency failures have explicit timeout, retry/backoff, circuit-breaker, queue/DLQ, and graceful-shutdown behavior as appropriate. Migrations have a safe forward path and a tested rollback or recovery plan. Backup claims require a restore drill, not only a successful backup command.
5. **Performance and capacity:** A reproducible benchmark uses representative data and an identified environment. Acceptance criteria come from a documented SLO or an approved capacity requirement. Report p50/p95/p99, throughput, resource use, and saturation where the system supports measurement. Local microbenchmarks do not certify production capacity.
6. **Observability and operations:** Operators can distinguish liveness from readiness, diagnose request/job failures, see dependency health, correlate events, receive actionable alerts, and follow a runbook. Telemetry is structured and privacy-safe.
7. **Release controls:** Required tests, static checks, dependency/security scans, migration checks, configuration validation, staged rollout, rollback triggers, and owner sign-off are documented and run in the target release workflow.
8. **External systems:** Production database, cache, queue, identity provider, payment provider, email/SMS provider, and cloud behavior are verified in an approved sandbox or deployment. A mock, fake, local SQLite run, or configuration file is not evidence of live integration.

## Decision rules

- **PASS / production-verified** requires direct evidence from the target-like deployment for every applicable gate, including real dependency and recovery checks.
- **GAP / not production-ready** applies when the service has a known safety or operational gap.
- **BLOCKED / not certified** applies when required environments, credentials, approvals, or measurements are unavailable.
- **NOT APPLICABLE** requires a short reason tied to the service's actual scope; do not use it to skip a difficult gate.
- Keep the repository's service coverage matrix accurate, but distinguish automated-test coverage from deployment evidence. Do not label “TESTED” as “production-ready.”
- Never deploy, run destructive migrations, move funds, or use live customer data without explicit authorization.

## Required report

Report the service and version, deployment/environment tested, gate-by-gate status, exact test/scan/benchmark/restore evidence, unresolved risks, rollback path, and the decision. State what was not verified. Do not turn unknown results into a success-shaped summary.
