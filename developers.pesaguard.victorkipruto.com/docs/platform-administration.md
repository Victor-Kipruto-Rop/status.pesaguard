# Developer Platform Administration (Phase 19)

Status: **THE SEPARATION IS BUILT AND TESTED. THE ADMIN CAPABILITIES THEMSELVES
ARE NOT.** Operator identity, tokens, the second filter chain, and the
authorization model are complete and unit-tested. There is **no controller**, so
none of the listed capabilities are reachable yet.

This phase is specifically internal administration required to operate the
Developer Platform. It is **not** the general PesaGuard administration platform.

## The separation

This is the substance of the phase, and it is built on three independent
mechanisms. Any one of them would be a convention; together they are a control.

### 1. A second filter chain, scoped by `securityMatcher`

`OperatorSecurityConfig` declares a chain matching only `/internal/**`, ordered
first. A request to an operator endpoint can only ever be handled there — it is not
a set of rules inside the developer chain where a later edit could reorder or
bypass them.

The developer chain was given an **explicit** matcher (`/api/**`, `/oauth/**`,
`/openapi/**`, `/actuator/**`). This was the subtlest part of the work: without
it, the developer chain is the fallback for *any* unmatched request and would
quietly serve `/internal/**` through developer session auth, undoing the entire
separation. It is now explicit so that stays true.

### 2. A distinct principal type

`AuthenticatedOperator` is not a variant of `AuthenticatedUser`. No developer
session token can be converted into one, because the only code that constructs it
is the operator token authenticator. A shared principal type with a role flag would
put the boundary in application code, where one missed check exposes every
tenant's credentials.

The operator filter accepts **only** an operator token; a developer session
cookie is not even parsed. `getCredentials()` returns null so the live token can
never reach a log or an error page.

The developer session filter is **not in the operator chain**, so no developer
session can be established there even if a token were somehow accepted.

### 3. A separate capability namespace

`OperatorCapability` is entirely separate from the tenant `Permission` catalog.
Tenant permissions are grantable by an organization owner to its own members;
operator capabilities cannot be, because they are not held by a tenant. Reusing
the tenant catalog would mean an owner could grant themselves
`platform.credentials.revoke` over every other tenant.

## Token design

Operator tokens are HMAC-SHA256, prefixed `pgop_`, signed with
`PESAGUARD_OPERATOR_HMAC_KEY` — a key **distinct** from the credential and audit
keys. Sharing one would make a developer API key an operator token by formatting
alone, and the whole separation rests on those tokens being unrelated. The key is
validated at startup: a missing or wrong operator key stops the deployment rather
than surfacing as an authentication failure during an incident.

Capabilities are **signed into the token, not looked up**. Revoking an operator
means rotating the key rather than editing a row, and there is no table someone
with partial database access could widen their own capabilities in.

Signature comparison is constant-time — it is network reachable, and an early
exit would leak the signature a byte at a time.

A malformed, forged, expired, or oversized token returns **empty**, never throws
and never distinguishes itself from a missing one. Probing an operator endpoint
learns nothing.

## Authorization model

Read and mutating capabilities are separated, so answering a support question does
not require the power to end an integration:

| Read | Mutating |
|---|---|
| `ORGANIZATIONS_READ`, `PROJECTS_READ`, `CREDENTIALS_READ`, `WEBHOOKS_READ`, `USAGE_READ`, `PRODUCTION_READ`, `SECURITY_READ`, `PLATFORM_CONFIG_READ` | `CREDENTIALS_SUSPEND`, `CREDENTIALS_REVOKE`, `PRODUCTION_REVIEW`, `SECURITY_RESOLVE`, `PLATFORM_CONFIG_WRITE` |

`PRODUCTION_REVIEW` is separate from `PRODUCTION_READ` because reviewing is a
decision — otherwise every read-only operator could approve a production grant.
`SECURITY_RESOLVE` is separate from `SECURITY_READ` for the same reason Phase 15
kept resolution distinct from detection: a reader must not be able to close a
finding.

**Every mutating action requires a stated reason**, sanitised of control
characters and bounded to 500. A revoke with no recorded reason cannot be
explained to the customer afterwards, and the audit trail would say only that
someone with platform access ended a working integration.

`AuthenticatedOperator` carries **no organization**. An operator is not
tenant-scoped, and an `organizationId` field would invite code that filters by it
and quietly returns the wrong customer's data. A test asserts the field is absent.

## Fail-closed

The operator chain matches each capability **explicitly** and ends in
`anyRequest().denyAll()`. An endpoint added under `/internal/` without a rule
matches nothing and is refused, rather than becoming reachable to every operator
by accident. Mutation paths are matched before the read path, so a read-only
credential cannot satisfy a write.

## Not implemented

- **No controller.** None of the eleven listed capabilities — view organizations,
  inspect projects, inspect/suspend/revoke credentials, inspect webhook health,
  inspect usage, inspect production requests, review verification, inspect
  security events, manage configuration — is reachable. The chain refuses
  everything under `/internal/` except `/internal/health`, because there is
  nothing to permit yet. That is the correct behaviour, not an oversight.
- **No audit recording of operator actions.** An operator revoking a customer's
  credential is exactly the kind of action the Phase 18 audit log exists to
  capture, and `AuditService` requires an `actorUserId` that an operator does not
  have. This is the most important gap: **the separation is in place but the
  accountability is not.**
- **No operator directory.** Operators are identified by token subject only.
- **No revocation list.** Revoking an operator means rotating the shared key,
  which invalidates every operator at once.
- **No integration test proving a developer session is refused on `/internal/**`**
  through the real filter chain. The unit tests prove the token service refuses a
  developer token; the chain wiring itself is unverified without a running
  application.
- **CORS is disabled on the operator chain**, which is correct, but there is no
  documented network-level separation.

## Decisions to revisit

- **One shared operator key.** Blast radius of a leak is every operator at once.
  Per-operator keys would fix that at the cost of a revocation list.
- **Application-level separation only.** Correct regardless of topology, but
  network isolation is a second layer worth adding.
- **Tokens are bearer credentials**, so they are as sensitive as passwords and
  need a rotation and expiry policy that does not exist yet.