# Environment access policies

Environment access policies add a role-specific authorization layer on top of the existing organization and project membership checks. With no policy configured for an environment, existing access is unchanged. After at least one policy is configured, non-administrator access requires a matching role policy for the requested capability. Organization owners and admins retain administrative access so they can recover a misconfigured environment.

Policies can target an organization role (`ORGANIZATION`) or a project member role (`PROJECT_MEMBER`). If a caller has a matching organization-role policy, that policy is authoritative; otherwise the active project-member role policy is considered. No matching policy means access is denied once the environment has policies. All normal project access checks still apply.

Supported capabilities:

| Capability | Applies to |
| --- | --- |
| `READ` | Environment details, history, limits, API-key inventory, credentials, usage/log filters, events, and webhook inventory |
| `WRITE` | Environment configuration and limits, webhook configuration/lifecycle, and event subscriptions/replay |
| `DEPLOY` | Promote an environment to the next tier |
| `ROTATE_CREDENTIALS` | Create/revoke/rotate API keys, environment credentials, and webhook signing secrets |
| `MANAGE_POLICIES` | Read and change environment access policies |

Any non-read capability also permits environment reads needed to use that capability. A policy's optional IP allowlist accepts IPv4/IPv6 addresses and CIDR ranges; a request outside the allowlist is denied. These checks use the platform's resolved remote address, not an arbitrary forwarding header.

Policy endpoints, all under `/api/v1/projects/{projectId}/environments/{environmentId}`:

- `GET /access-policies` — list the environment's policies
- `PUT /access-policies` — create or replace a role policy with `subjectType`, `subjectRole`, `permissions`, and optional `ipAllowlist`
- `DELETE /access-policies/{policyId}` — remove one policy

Policy changes require the normal environment update permission and project-management authorization, and are recorded in the organization audit trail. Deleting the final policy restores the existing project-based access behavior. The Developer Platform's environment detail page provides the same create/update and removal workflow.
