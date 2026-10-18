-- Phase 76: API scope lifecycle additions (portal-resolvable only).
--
-- Portal RBAC roles are defined in code; this migration only manages the
-- registry-backed API scopes and their grant restrictions.
--
-- This migration therefore adds:
--   1. Inventory rows for portal-resolvable scopes in api_scopes.
--   2. Explicit restrictions for scopes requiring elevated review.

begin;

-- 1. Inventory rows --------------------------------------------------------
-- One row per portal-resolvable scope. This is the single source of truth the
-- seed above and the portal authority service look up.

insert into api_scopes (name, description, category, resource, action, restricted)
values
    ('organization:read', 'Read safe organization context for the portal.', 'organization', 'organization', 'read', false),
    ('project:read', 'Read safe project context for the portal.', 'project', 'project', 'read', false),
    ('environment:read', 'Read safe environment context for the portal.', 'environment', 'environment', 'read', false),
    ('developer:read', 'Read developer-portal resources: projects, environments and credential metadata.', 'developer', 'developer', 'read', false),
    ('developer:write', 'Manage developer-portal resources: create credentials and environments.', 'developer', 'developer', 'write', true)
on conflict (name) do nothing;

-- 2. Restrictions that must be raised before a portal grant succeeds ---------
-- Grants for these scopes require an explicit, attributable elevation and
-- security review. Enforcement is done by the portal authority service.

insert into api_scope_restrictions (scope_name, reason, requires_security_review)
values
    ('developer:write', 'Creates credentials and environments, which can escalate access.', true)
on conflict (scope_name) do nothing;

commit;
