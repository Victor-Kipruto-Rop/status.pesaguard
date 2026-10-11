---
name: pesaguard-service-implementation
description: "Implements or hardens PesaGuard backend services from the numbered service inventory. Use for service work across API, identity, transactions, ingestion, Daraja, reconciliation, reporting, security, notifications, infrastructure, data, production operations, or API governance; includes design, tests, security, performance, observability, review, and documentation gates."
---

# PesaGuard Service Implementation

Use this workflow for one service or one tightly coupled, user-approved service slice. Do not attempt to implement the full inventory in one change.

## Required context

1. Read the relevant rows in [the service coverage matrix](../../../docs/testing/SERVICE_COVERAGE_MATRIX.md).
2. Read [the product scope](../../../docs/product/PRODUCT_SCOPE.md) and relevant architecture documentation.
3. Load the existing focused skills for [incremental implementation](../incremental-implementation/SKILL.md), [test-driven development](../test-driven-development/SKILL.md), [security and hardening](../security-and-hardening/SKILL.md), [observability](../observability-and-instrumentation/SKILL.md), [performance](../performance-optimization/SKILL.md), [review](../code-review-and-quality/SKILL.md), and [documentation](../documentation-and-adrs/SKILL.md) when their gates apply.
4. Identify the requested service number, code owner, driver port, driven ports, existing implementations, callers, tests, and the exact gap. Reuse current boundaries; do not add a duplicate service layer just to match a service name.

## Scope and safety rules

- Treat the inventory as a catalog, not permission to expand product scope. In particular, PesaGuard does not initiate payouts, refunds, settlements, or other movement of funds. Do not implement or expose those capabilities without a separate, approved scope and design.
- Preserve tenant isolation across HTTP, persistence, jobs, events, caches, exports, and provider callbacks. Derive tenant identity from trusted authenticated context and constrain every tenant-owned read and write.
- Preserve financial and audit integrity. Use explicit state transitions, idempotency, append-only audit records, transaction boundaries, and recovery behavior where the service requires them.
- Do not invent provider behavior, production credentials, service-level objectives, sunset dates, infrastructure guarantees, or test results. Ask for a decision when ambiguity changes a security boundary, financial behavior, schema, or externally visible contract.
- Keep unrelated worktree changes untouched. Inspect the target files and current diff before editing.

## Implementation loop

Complete and verify each step before moving to the next major service:

1. **Design.** State the behavior, invariants, trust boundaries, driver/driven ports, inputs, outputs, failure modes, data ownership, concurrency risks, and acceptance criteria. Include tenant isolation and rollback/recovery behavior in the design.
2. **Implement tests first.** Add a failing test for each new observable requirement. Use unit tests for isolated algorithms and integration tests through the real HTTP, queue, event, CLI, or service entry point for behavior. A unit test does not replace boundary-level coverage.
3. **Implement the smallest complete slice.** Reuse existing services, schemas, shared helpers, and dependency-injection patterns. Validate external input at the boundary. Surface failures through the repository's error, logging, and alerting conventions; do not silently default or return success-shaped fallbacks.
4. **Unit-test.** Exercise deterministic rules, validation, state transitions, and error mapping. Include boundary values and expected concrete outputs.
5. **Integration-test.** Drive the service through its public entry point and real internal stack. Use isolated temporary databases/queues where supported. Mock only external providers that have no test sandbox.
6. **Security-test.** Cover authorization and cross-tenant access, untrusted input, replay/idempotency, secret and PII handling, resource exhaustion, and race conditions relevant to this service. Use an adversarial review when the change affects a trust boundary.
7. **Performance-test.** Measure the real hot path with realistic data and a named environment. Compare with an existing baseline or an explicit user-approved threshold. Do not use a nonzero-throughput assertion as proof of scalability and do not invent an SLO.
8. **Observe.** Verify structured logs, metrics, traces, correlation IDs, health/readiness signals, and alert conditions where applicable. Confirm telemetry contains no credentials, tokens, or unnecessary personal data.
9. **Review.** Inspect the final diff and run relevant linters, type/syntax checks, and focused regression suites. Review compatibility, tenant boundaries, data integrity, lock ordering, duplicate functionality, network calls, and failure paths.
10. **Document.** Update the service coverage matrix and directly related architecture/API/operations docs with implementation paths, tests actually run, measured performance evidence, known gaps, rollback behavior, and deployment verification limits.

Meet the repository's configured coverage floor for changed code; when a coverage tool exists, target at least 80% of touched production lines. Prefer boundary-level integration coverage for observable behavior. If the repository cannot measure touched-file coverage, state that limit instead of implying it passed.

## Phase gate

Before starting the next major service, answer these for the completed slice:

- Does it fit the current architecture and reuse existing boundaries?
- Does it preserve tenant isolation and data integrity?
- Can concurrent requests or retries create races, duplicates, or partial state?
- Does it introduce a security-boundary or privacy problem?
- Does it duplicate existing functionality or add unnecessary network calls?
- Is there a measured scalability bottleneck or an unverified performance claim?
- Can operators observe and diagnose success and failure?
- Are unit, integration, security, and performance checks present where applicable?
- Can the change be rolled back or recovered without data loss?

Fix any material finding before continuing. If evidence or an external dependency is unavailable, record the blocker and mark the gate incomplete. Never equate a passing local test suite with production certification.
