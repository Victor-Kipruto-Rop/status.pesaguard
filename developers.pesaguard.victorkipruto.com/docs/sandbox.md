# Sandbox Platform

A sandbox is an isolated environment for exercising an integration without
touching production.

This document describes what has been built. It is explicit about what has not,
because the isolation guarantee is the entire point of this subsystem and a
partial implementation of it is worse than none.

## The guarantee

```
sandbox  x  production  =  never
```

A sandbox must never accidentally invoke production functionality. This is
enforced in three independent places, deliberately redundant — either one alone
would be insufficient.

### 1. In the type system

`SandboxIsolation` is the capability token that authorises sandbox execution. It
is minted only by `forSandboxEnvironment(...)`, which verifies the environment is
`SANDBOX`. Crucially:

- There is **no method** that returns a production capability.
- There is **no conversion** from sandbox to production.
- No method accepts an environment id that could be production.

So "sandbox code calling production code" is not a bug someone has to remember
not to write — it is a construct that cannot be written. A sandbox execution path
takes a `SandboxIsolation`; to reach production it would need a different token
that has no constructor.

The runtime check inside the factory is deliberately redundant with the type
system: the compiler proves the call site, the runtime check proves the data. The
compiler cannot see a UUID fetched from a database; a runtime check can be
forgotten.

### 2. In the schema

`V9__sandbox_platform.sql` and `V10__sandbox_isolation_constraints.sql` carry the
invariant as data:

- `sandbox_isolation_guards` has `check (environment_type = 'SANDBOX')`.
- `sandbox_executions` has `check (environment_type = 'SANDBOX')`.
- `V10` adds composite foreign keys on
  `(environment_id, organization_id, project_id)` referencing
  `project_environments(id, organization_id, project_id)` on **all three** tables:
  `sandboxes`, `sandbox_isolation_guards` and `sandbox_executions`.

V9 alone was necessary but not sufficient: it checked a string that a buggy caller
could have written honestly while naming someone else's environment. V10 makes the
database refuse a sandbox that names an environment outside its own tenant or
project. It required adding a unique index on
`project_environments(id, organization_id, project_id)`, which V3 did not have.

A production environment id cannot be written into any of these tables — not by a
buggy migration, not by a direct `psql` session, not by anything that does not go
through the application.

### 3. In the tests

`SandboxIsolationTest` asserts the invariant rather than describing it: production,
staging and development are all refused, as are cross-environment and
cross-organization resources. `SandboxServiceTest` proves the gate at request
time: a sandbox cannot be *created* against production, a suspended or expired
sandbox cannot execute, and an **unpinned** sandbox cannot execute.

That last one matters. Without the guard lookup, anyone who could name any sandbox
id could execute without ever proving it was pinned. Absence of proof is not proof
of isolation, so the answer is no.

## Lifecycle

```
PROVISIONING --activate--> ACTIVE --suspend--> SUSPENDED --resume--> ACTIVE
                            |        |                                  |
                            |        +--delete--> DELETED <-----------+--delete-->
                            |
                            +--expire--> EXPIRED --delete--> DELETED
```

| State | Execution | Notes |
| --- | --- | --- |
| `PROVISIONING` | denied | Created but not usable |
| `ACTIVE` | **allowed** | The only executable state |
| `SUSPENDED` | denied | Data retained; resumable |
| `EXPIRED` | denied | Not resumable; must be deleted |
| `DELETED` | denied | Terminal |

## What has been built

- `Sandbox` aggregate with the full lifecycle
- `SandboxStatus` state machine, `SandboxLimits`, `SandboxExecution`,
  `SandboxExecutionKind`, `SandboxExecutionOutcome`, `SandboxIsolationGuard`,
  `SandboxHistory`
- `SandboxIsolation` — the isolation capability token
- `SandboxIsolationViolation` — raised at the boundary
- `SandboxService` — create, activate, suspend, resume, reset, delete, expire-sweep,
  plus `requireExecutableIsolation`, the gate every execution path calls
- `SandboxExecutionService` — the single execution entry point
- `SandboxLimitsService` — quota management
- `SandboxController` — lifecycle, execution history, limits
- `GET /api/v1/sandbox/transactions` accepts a valid `transactions:read` API key
  bound to an active sandbox environment. It returns persisted transactions only;
  when the backend has no transaction records, it returns an empty collection.
- Repositories for sandboxes, guards, limits, executions and history
- Eight `sandbox:*` RBAC permissions, held by OWNER, ADMIN and DEVELOPER
- `V9` and `V10` migrations, with append-only triggers on execution and history
- OpenAPI contract
- Tests for the lifecycle, the isolation invariant and the request-time gate

## Execution

All six execution kinds — test requests, API flows, error responses, authentication,
webhooks and events — route through **one** method, `SandboxExecutionService.execute`.
They differ only in the handler and the recorded kind. One entry point means one
place where isolation is established, rather than six places where it might be
forgotten.

Execution cannot proceed without a `SandboxIsolation`, because
`SandboxExecution.record` requires one: there is no constructor taking a raw
environment id. The handler receives the token and a timeout already clamped to the
sandbox's ceiling.

Denials are recorded, not thrown at the caller. An isolation violation or an
exceeded quota is the expected result of a well-behaved sandbox, and reporting it
as a failure would make a well-isolated sandbox look broken. `DENIED` is a distinct
outcome from `FAILED` for exactly this reason.

## What has NOT been built

- **No concrete operation handlers.** `SandboxOperationHandler` is the extension
  point; nothing implements it yet. The business APIs that a sandbox would
  exercise (payments, transactions, reconciliation, fraud) do not exist in this
  codebase — phases 1-9 built the platform, not those APIs. Registering handlers
  is the next integration point.
- The first-request transactions route is intentionally a fixed read-only
  verification response, not a general transaction API and not a payment
  simulator. It cannot accept a target URL or initiate a financial operation.
- **Reset does not yet clear execution history.** `recordReset` increments a
  counter; the deletion of prior executions is not implemented.
- **The expiry sweep is not scheduled.** `expireLapsed` exists but nothing calls
  it. This is not a correctness problem: `effectiveStatus` already makes an
  overdue sandbox inert, so a sweep that never runs costs only a stale column.
- **No rate limiting is enforced.** `requestsPerMinute`, `burstRequests` and
  `maxEventsPerMinute` are stored and validated but not yet applied per request,
  unlike the timeout and body-size limits which are.

## Known gaps and risks

- **No database-backed integration tests.** The check constraints, foreign keys and
  append-only triggers in `V9` and `V10` have never run against a real PostgreSQL
  instance, because Docker is unavailable in the development environment. The
  schema-level isolation guarantee is therefore **unverified**, including the V10
  composite foreign keys. Treat it as a design intention until it has executed.
- **No physical data separation.** Sandbox and production data share one database
  and schema. Isolation is enforced by the code paths that reach them, not by the
  storage layer. If a sandbox must be isolated even from a compromised application
  process, it needs its own database or schema.
- **Execution history is unbounded in practice.** `maxHistoryEntries` is stored but
  no trimming job runs yet, so a long-lived sandbox accumulates rows.
