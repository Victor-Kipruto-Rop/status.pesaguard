# Architecture

## Runtime shape

The backend is a Spring Boot modular monolith. HTTP controllers are thin; application services own use-case transactions; repositories and JPA entities are infrastructure/domain adapters. PostgreSQL is the system of record and Flyway is the schema authority.

```text
HTTP filter/security
        |
controllers -> application services -> JPA repositories -> PostgreSQL
                                      \-> audit append service
```

## Tenant isolation

The authenticated membership supplies the organization ID. Controllers never accept an organization ID as an authority input. Every project/environment/API-key query includes the authenticated organization ID, and cross-tenant resource IDs return the same not-found response as absent resources.

Database composite foreign keys additionally enforce that an environment belongs to the same organization/project and that an API key belongs to the same organization/project/environment. These are defense-in-depth controls; application checks remain required for clear error behavior.

## Organizations, memberships, and tenancy

An organization is the tenant. `tenant_id` is a deliberate alias of the authenticated `organization_id`; it is never accepted from a request body, query string, or path. `TenantContextHolder` exposes that identity per request and `TenantAwareCache` keys every entry by tenant, so cached values cannot cross organizations.

That alias is local to the Developer Platform. The separate Core API tenant ID is derived from the canonical lowercase UUIDs for the organization, project, and environment:

```text
dp_ + lowercase_hex(SHA-256(UTF-8("developer-platform:v1|<organization_id>|<project_id>|<environment_id>")))
```

Both services use this versioned mapping; the Core API verifies it when synchronizing a key and exposes tenant resolution only through its signed internal endpoint. Never substitute the Developer Platform organization UUID for the Core API tenant ID or accept a caller-supplied tenant ID as authoritative. The shared test vector (`...0001`, `...0002`, `...0003`) resolves to `dp_2934bbfdc6fee932108c14a00ca22a9e8b6217d596b1d0988edefd178e1cb1b7`.

Organization status moves through `ACTIVE`, `SUSPENDED`, `PENDING`, `DISABLED`, and `DELETED`. Deletion is soft: the row keeps its name, slug, owner, and audit sequence, and the record is excluded from tenant-scoped reads. Lifecycle transitions revoke every active organization session in the same transaction.

Memberships are append-only in meaning. Role changes, suspensions, and removals mutate the current membership row and write an immutable `organization_membership_history` row in the same transaction; removal is represented as `REVOKED` rather than a destructive delete. Ownership is transferred explicitly to an active non-owner member, which demotes the previous owner to `ADMIN`, so an organization can never end up without an owner.

Invitations store only a SHA-256 token hash. The raw token is returned exactly once, at creation, and acceptance requires the token plus the invited email. Expired, revoked, and already-accepted invitations fail closed.

Login automatically selects the user's last-accessed active organization, falling
back to an active membership when none is saved or that membership is no longer
available. An explicit `organizationId` may select another active membership.
Unknown or inactive selections return the same invalid-credentials response as a
wrong password.

## Transactions and audit

Registration, organization lifecycle, membership and invitation changes, project creation, environment creation, API-key issuance/revocation, and session changes use Spring transactions. Audit append obtains a pessimistic organization-row lock, increments the organization sequence, reads the prior event, computes an HMAC, and flushes the append-only event in the same transaction as the business write.

Audit metadata is canonicalized deterministically and excludes raw passwords, bearer tokens, and API-key secrets. Hash version 2 additionally binds the client IP address and user agent; verification supports both version 1 and version 2 records so pre-existing chains stay verifiable. The database trigger rejects audit updates and deletes. Audit verification checks sequence continuity, previous-hash linkage, and the dedicated audit HMAC.

## Security boundaries

Bearer sessions are opaque random tokens. Only SHA-256 token hashes are persisted. API-key secrets are returned only by the issue response; the database stores a distinct HMAC hash and the list response contains only metadata. CORS is explicit and does not allow credentials. CSRF is disabled because the API is stateless bearer-token based; browser integrations must not place bearer tokens in cookies.

## Deliberate omissions

This slice does not implement OAuth/OIDC, MFA, billing, production environments, provider webhooks, reconciliation, payment execution, asynchronous jobs, or API-key authentication for resource endpoints. Those capabilities require separate contracts and security reviews.
