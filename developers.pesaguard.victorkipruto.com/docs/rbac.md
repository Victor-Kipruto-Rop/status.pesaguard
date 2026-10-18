# Authorization (RBAC)

Authorization is permission-based. Controllers are thin; the decision is made in the
application service via `AuthorizationService.requirePermission(...)`, which
re-reads the caller's membership on every request. A role annotation alone is never
the authorization decision.

## Layers

1. **Authentication** — an opaque bearer session resolved to `AuthenticatedUser`.
2. **Membership** — the caller's organization membership must be `ACTIVE`. A
   suspended or revoked member holds no permissions even if their token is still
   valid.
3. **Effective permissions** — built-in role permissions ∪ permissions of every
   active custom role assigned to the caller.
4. **Resource scoping** — project/environment operations additionally verify the
   resource belongs to the caller's organization, and project access additionally
   requires project membership unless the caller is an organization owner/admin.

## Built-in roles

| Role | Summary |
| --- | --- |
| OWNER | Full administration and production access **review** |
| ADMIN | Administration and production access **request** |
| DEVELOPER | Build projects/environments and manage own credentials; may request production access |
| SECURITY | Reviews production access, rotates/revokes credentials, reads audit; cannot change the organization or projects |
| ANALYST | Read-only including usage and audit |
| VIEWER | Read-only |

No built-in role holds both `production_access:request` and
`production_access:review`, so request and approval always sit in different hands.

## Custom roles

Custom roles are organization-scoped bundles of catalog permissions. Two rules are
enforced on every write:

- **No privilege escalation.** A role may only bundle permissions the acting user
  already holds (`PRIVILEGE_ESCALATION_BLOCKED`). Delegating can never widen
  authority past the delegator's own.
- **Separation of duties.** A role may not contain both production-access request
  and review.

Unknown permission strings are rejected (`INVALID_PERMISSION`). Permission strings
are canonical lowercase and case-sensitive: `PROJECT:READ` is not accepted.

Role assignments are revoked, never deleted, so role history remains inspectable.
Archiving a role stops it granting anything without deleting the record.

## Production access

Requesting production access requires `production_access:request`; approving
requires `production_access:review`; the reviewer may never be the requester
(`SELF_REVIEW_FORBIDDEN`, also enforced by a database check constraint). Grants must
be time-boxed with `expiresIn` of at most 7 days, and lapse automatically.

## OAuth application management

Six dedicated permissions govern OAuth application lifecycle. They are
deliberately distinct from `webhook:*`, so revoking webhook rights does not
silently affect OAuth clients and vice versa:

| Permission | Governs |
| --- | --- |
| `oauth_application:read` | List applications |
| `oauth_application:create` | Register an application |
| `oauth_application:update` | Edit an application, verify |
| `oauth_application:rotate` | Rotate a client secret |
| `oauth_application:suspend` | Suspend and resume |
| `oauth_application:revoke` | Revoke an application |

`OWNER`, `ADMIN` and `DEVELOPER` hold all six. `SECURITY`, `ANALYST` and `VIEWER`
hold none.

## Not yet enforced

- Portal `webhook:*`, `usage:read` and `audit:read` permissions are enforced by
  their respective APIs. API credentials use separate scopes such as plural
  `webhooks:read`; see [the scope registry](./scopes.md).
- `EnvironmentAccessPolicy` rows exist but are not consulted at request time; they are
  stored and exposed only.
- OAuth applications are scoped to an organization, not to a project and environment
  pair. See `docs/oauth.md`.