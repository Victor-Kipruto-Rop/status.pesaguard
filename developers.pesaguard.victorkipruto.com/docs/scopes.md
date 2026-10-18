# API Scopes and Access Control

PesaGuard's central API-access model. This document describes the system as it is
implemented, including the parts that are deliberately incomplete.

## Scopes are not RBAC permissions

These are two different namespaces and they are kept apart on purpose:

| | Grants | Example |
| --- | --- | --- |
| **Scope** | what a credential may call over the API | `webhooks:read` |
| **RBAC permission** | what a human may do in the portal | `webhook:read` |

Note the plural. `webhooks:read` is not `webhook:read`. Merging them would mean
revoking a portal permission silently revoked API access, or granting a portal
permission handed out API access the credential was never scoped for. Both are
real privilege-escalation paths, so the namespaces stay separate.

A scope is a **permission to call a PesaGuard API**. It is not an implementation of
that API.

## The registry

Seeded by `V7__api_scopes.sql` and extended by `V42__api_key_data_scopes.sql`.
The registry is owned by code: an operator may annotate a scope, but cannot invent one. An unknown scope is **rejected, not
created**, so a typo in a client integration fails loudly instead of quietly
creating a scope that grants nothing and looks legitimate in a listing.

| Scope | Category | Restricted |
| --- | --- | --- |
| `transactions:read` | transactions | no |
| `transactions:write` | transactions | **yes** |
| `payments:read` | payments | no |
| `payments:write` | payments | **yes** |
| `reconciliation:read` | reconciliation | no |
| `reconciliation:write` | reconciliation | **yes** |
| `fraud:read` | fraud | no |
| `usage:read` | usage | no |
| `audit:read` | audit | no |
| `events:read` | events | no |
| `webhooks:read` | webhooks | no |
| `webhooks:write` | webhooks | **yes** |
| `developer:read` | developer | no |
| `developer:write` | developer | **yes** |

`fraud:read` has no write counterpart by design: PesaGuard decides fraud, clients
never do.

Categories are a presentation and review grouping, **not** a permission boundary.
Holding `payments:read` grants nothing else in the payments category.

Read-only API-key data routes are exposed under `/api/v1/key-data`. They
authenticate the presented key directly and do not turn it into a user session:
login, MFA, session, account, organization, membership, role, and credential
administration routes remain session-only. Usage queries are pinned to the key's
project and environment; audit, event, and endpoint resources are limited to the
key's project, with subscriptions and delivery attempts further restricted to
its environment.

| Route | Required API-key scope |
| --- | --- |
| `GET /api/v1/key-data/usage` | `usage:read` |
| `GET /api/v1/key-data/audit-events` | `audit:read` |
| `GET /api/v1/key-data/events` | `events:read` |
| `GET /api/v1/key-data/events/catalog` | `events:read` |
| `GET /api/v1/key-data/events/subscriptions` | `events:read` |
| `GET /api/v1/key-data/events/deliveries` | `events:read` |
| `GET /api/v1/key-data/webhooks` | `webhooks:read` |

These routes accept `Authorization: Bearer <api-key>`. They return 401 for an
invalid, expired, suspended, revoked, IP-restricted, or resource-inactive key,
and 403 when the key lacks the route's exact scope. Key use is recorded only
after a successful data response.

### Matching

Scopes are matched by **exact value**. No prefix matching, no wildcards, no case
folding. `WEBHOOKS:READ` is not `webhooks:read`; it is a different string that does
not exist in the registry. The same `resource:action` grammar is enforced by a
check constraint in the database and by `ApiScope` in code.

### Versions

Each scope carries a version. When a grant is recorded, the version at grant time
is stored alongside it. A scope granted at version 1 and the same scope granted at
version 2 are distinguishable, so "who was allowed to do this, and under what
meaning" stays answerable after a semantic change.

### Restricted scopes

A restricted scope requires a deliberate grant. Each one records *why* in
`api_scope_restrictions` — an unexplained restriction is indistinguishable from a
bug months later. Restricting a scope requires a stated reason.

### Deprecated scopes

Deprecating a scope **does not stop it working**. It marks the scope, records the
replacement and the reason, and the access decision reports `SCOPE_DEPRECATED` in
its trace so callers can surface it. Silently revoking a deprecated scope would
break every integration that has not migrated; an outage is not a deprecation
## Assignment

Scopes are granted to a credential, and each grant records who granted it, when,
and at which scope version. Re-granting an existing scope is a no-op, so a retried
request does not fabricate a second grant. Revoking a grant is separate from
revoking the credential, and the revoked row survives — the audit trail must show
that a scope was once held, not that it never was.

This table is the auditable record. The credential's own `scopes` column is the
fast path read by the Phase 05 API-key flow; the two are not yet reconciled
automatically (see Known limitations).

## Access decision

Every API request is evaluated against eight factors, in a fixed order:

1. **Identity** — is there an authenticated principal
2. **Organization** — is it real and active
3. **Project** — is it present and active in the request context
4. **Environment** — does it belong to that project and is it active
5. **Credential** — does it exist and belong to this organization
6. **Credential binding** — is it scoped to this project and environment
7. **Role** — does the actor's role permit the action
8. **Scope** — is the scope known, granted, and not blocked
9. **Credential status** — is the credential `ACTIVE` and unexpired

Three properties matter more than the ordering itself:

- **Fail closed.** A factor that cannot be resolved denies. Nothing defaults to
  allow on an unexpected path. A decision that evaluated *no* factors at all is
  refused with `NO_FACTORS_EVALUATED` rather than treated as a pass.
- **The first failure is the reported reason.** Evaluation stops there, so a
  reason code is never misleading.
- **Credential status is checked last, deliberately.** A revoked credential should
  report `CREDENTIAL_STATUS_BLOCKED`, which is actionable, rather than an
  incidental scope failure discovered first.

A denial is a returned value, never an exception, so the caller decides whether to
surface it as 403 or to record it.

### Reason codes

`ALLOWED`, `NO_FACTORS_EVALUATED`, `IDENTITY_UNAUTHENTICATED`, `IDENTITY_INACTIVE`,
`ORGANIZATION_UNAVAILABLE`, `PROJECT_UNAVAILABLE`, `ENVIRONMENT_UNAVAILABLE`,
`CREDENTIAL_UNAVAILABLE`, `CREDENTIAL_CONTEXT_MISMATCH`, `ROLE_INSUFFICIENT`,
`SCOPE_NOT_ASSIGNED`, `SCOPE_UNKNOWN`, `SCOPE_DEPRECATED`, `SCOPE_RESTRICTED`,
`CREDENTIAL_STATUS_BLOCKED`.

These are operator-facing: they appear in API error bodies and in the decision
table. Renaming one is a breaking change for anyone integrating against error
handling.

### Explainability

Every decision, allowed or denied, is written to `api_access_decisions` with a
full per-factor trace:

```
IDENTITY_UNAUTHENTICATED=PASS(9f3c...); ORGANIZATION_UNAVAILABLE=PASS(2a71...);
CREDENTIAL_UNAVAILABLE=PASS(4b02...); CREDENTIAL_CONTEXT_MISMATCH=PASS(bound to
project and environment); SCOPE_UNKNOWN=PASS(payments:read);
SCOPE_NOT_ASSIGNED=FAIL(payments:read)
```

The table is **append-only** — the database rejects updates and deletes on it,
including from an operator cleaning up "noisy" rows. `GET
/api/v1/scopes/decisions/denied` exposes recent denials so "why was my key
refused?" is answerable from the product rather than only from the database.

## Tenant isolation

The credential is looked up by `(id, organizationId)`. A credential belonging to
another organization produces an empty result and is refused at factor 5 before
any of its properties are inspected.

## Permissions

Catalog reads need `credential:read`; granting a scope needs
`credential:create`; revoking a grant needs `credential:revoke`; annotating the
registry needs `credential:update`. Annotating a scope is deliberately separate
from `credential:rotate` and `credential:revoke`: marking a scope deprecated must
not imply the power to mint a new secret or destroy one.

## Known limitations

- **The role factor is coarse.** `roleAllows` currently requires an authenticated
  member of an active organization holding at least one granted authority. The
  real constraint is the scope grant. This is deliberately permissive rather than
  pretending to enforce a finer model it does not implement.
- **Project and environment are assumed resolved.** The decision service records
  their presence as a factor but does not re-query them; the calling endpoint has
  already verified them. It does not independently confirm they are `ACTIVE`.
- **Assignment and the credential's `scopes` column are not yet reconciled.**
  Granting via the assignment table records the grant but does not yet rewrite
  the credential's `scopes` column that the Phase 05 API-key path reads. Until
  that is wired, a credential must be issued with the scopes it needs *and* have
  them granted, or the assignment table will not affect API-key authentication.
- **No database-backed integration tests executed.** The tests are unit tests
  against domain objects and mocked repositories. The append-only trigger, the
  check constraints and the foreign keys in `V7` have never run against a real
  PostgreSQL instance, because Docker is unavailable in the development
  environment. Treat the schema as unverified until it does.
strategy.