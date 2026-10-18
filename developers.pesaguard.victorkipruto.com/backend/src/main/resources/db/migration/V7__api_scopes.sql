-- Phase 07: API scope registry and access control.
--
-- A scope is a permission to call a PesaGuard API. It is NOT an implementation of
-- that API, and it is NOT the same thing as an RBAC permission:
--
--   scope      webhooks:read   -> what a credential may call over the API
--   permission webhook:read    -> what a human may do in the developer portal
--
-- Keeping the two namespaces separate is deliberate. Conflating them would mean
-- revoking a portal permission silently revoked API access, or granting a portal
-- permission granted API access the credential was never scoped for.
--
-- The registry is seeded here and owned by code. Rows may be annotated (a scope may
-- be deprecated or restricted by an operator) but a scope is never invented at
-- runtime: an unknown scope is rejected, not created, so a typo in a client
-- integration fails loudly instead of silently granting nothing.

create table api_scopes (
    -- Canonical lowercase form, e.g. 'transactions:read'. Matched exactly.
    name varchar(64) primary key,
    -- Human-readable explanation shown in the portal and returned by the catalog.
    description varchar(500) not null,
    -- Grouping for the UI and for bulk permission review, e.g. 'transactions'.
    category varchar(32) not null,
    -- Part of the resource name before the colon.
    resource varchar(48) not null,
    -- Part after the colon. Read scopes are always non-mutating.
    action varchar(24) not null,
    -- Bumped when a scope's meaning changes. Recorded on assignment so a grant can
    -- be interpreted as the author intended it.
    version int not null default 1,
    -- A restricted scope needs an explicit, elevated grant.
    restricted boolean not null default false,
    -- Deprecated scopes still authenticate but are reported as such so clients can
    -- migrate. They are never silently removed.
    deprecated boolean not null default false,
    -- Set when deprecated, naming the scope to migrate to. Null otherwise.
    replaced_by varchar(64),
    -- Operator note explaining why a scope was deprecated or restricted.
    change_reason varchar(500),
    -- Optimistic lock for concurrent annotation of a scope row.
    scope_version_lock bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint api_scopes_version_positive check (version > 0),
    constraint api_scopes_name_format
        check (name ~ '^[a-z][a-z0-9-]{1,47}:[a-z][a-z0-9-]{1,23}$'),
    constraint api_scopes_resource_action_consistent
        check (name = resource || ':' || action),
    constraint api_scopes_replacement_is_known
        check (replaced_by is null or replaced_by <> name),
    constraint api_scopes_replacement_only_when_deprecated
        check (deprecated or replaced_by is null)
);

create index if not exists api_scopes_category_idx on api_scopes(category);
create index if not exists api_scopes_deprecated_idx on api_scopes(deprecated);

-- Which scopes require an elevated grant to assign. A restricted scope without a
-- row here is simply restricted; this table records the operator's stated reason,
-- which matters when someone asks why their key was refused.
create table api_scope_restrictions (
    scope_name varchar(64) primary key references api_scopes(name),
    reason varchar(500) not null,
    -- When true, granting this scope is recorded as requiring security review so
    -- the grant is visible to reviewers. Enforcement is in the access decision.
    requires_security_review boolean not null default false,
    created_at timestamptz not null default now()
);

-- Records which scope version a credential was granted. Assigning scope X at
-- version 1 and later being granted scope X at version 2 are different grants and
-- must be distinguishable when a semantic change is questioned.
create table api_key_scope_assignments (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    api_key_id uuid not null references api_keys(id) on delete cascade,
    scope_name varchar(64) not null references api_scopes(name),
    -- The registry version at the moment of the grant.
    scope_version int not null,
    granted_by uuid not null references users(id),
    granted_at timestamptz not null default now(),
    revoked_at timestamptz,
    revoked_by uuid references users(id),
    revocation_reason varchar(500),
    constraint api_key_scope_assignments_unique unique (api_key_id, scope_name)
);

create index if not exists api_key_scope_assignments_key_idx
    on api_key_scope_assignments(api_key_id, granted_at desc);

-- Records every access decision with its per-factor trace. Denials are what an
-- operator is asked to explain, so the explanation is stored rather than
-- reconstructed later from memory.
create table api_access_decisions (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    user_id uuid references users(id),
    api_key_id uuid references api_keys(id),
    project_id uuid,
    environment_id uuid,
    requested_scope varchar(64),
    allowed boolean not null,
    -- The first factor that failed, e.g. 'SCOPE_NOT_ASSIGNED'.
    reason_code varchar(48) not null,
    -- Full per-factor outcome so a decision can be explained, not just asserted.
    factor_trace text not null,
    request_id varchar(64),
    remote_address varchar(45),
    decided_at timestamptz not null default now()
);

create index if not exists api_access_decisions_key_idx
    on api_access_decisions(api_key_id, decided_at desc);
create index if not exists api_access_decisions_denied_idx
    on api_access_decisions(organization_id, allowed, decided_at desc);

-- The decision log is evidence. It must not be edited after the fact, including
-- by an operator cleaning up "noisy" rows.
create or replace function prevent_api_access_decisions_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'api_access_decisions is append-only';
end;
$$;

drop trigger if exists api_access_decisions_append_only on api_access_decisions;
create trigger api_access_decisions_append_only
    before update or delete on api_access_decisions
    for each row execute function prevent_api_access_decisions_mutation();

-- Seed catalog. Insert-only so re-running is safe, and so an operator who has
-- annotated a row does not have their changes silently overwritten.
insert into api_scopes (name, description, category, resource, action, restricted)
values
    ('transactions:read', 'List and retrieve transactions for a project environment.', 'transactions', 'transactions', 'read', false),
    ('transactions:write', 'Create, update and reverse transactions. Alters recorded financial state, so it is restricted.', 'transactions', 'transactions', 'write', true),
    ('payments:read', 'List and retrieve payments for a project environment.', 'payments', 'payments', 'read', false),
    ('payments:write', 'Initiate, capture and refund payments. Moves money, so it is restricted.', 'payments', 'payments', 'write', true),
    ('reconciliation:read', 'List reconciliation runs and their matches and exceptions.', 'reconciliation', 'reconciliation', 'read', false),
    ('reconciliation:write', 'Trigger reconciliation runs and resolve exceptions. Alters financial evidence, so it is restricted.', 'reconciliation', 'reconciliation', 'write', true),
    ('fraud:read', 'List and retrieve fraud signals, scores and alerts. Read-only: PesaGuard decides fraud, clients never do.', 'fraud', 'fraud', 'read', false),
    ('webhooks:read', 'List webhook endpoints and inspect recent delivery attempts.', 'webhooks', 'webhooks', 'read', false),
    ('webhooks:write', 'Create, rotate and delete webhook endpoints and replay deliveries.', 'webhooks', 'webhooks', 'write', true),
    ('developer:read', 'Read developer-portal resources: projects, environments and credential metadata.', 'developer', 'developer', 'read', false),
    ('developer:write', 'Manage developer-portal resources, including creating credentials and environments.', 'developer', 'developer', 'write', true)
on conflict (name) do nothing;

insert into api_scope_restrictions (scope_name, reason, requires_security_review)
values
    ('transactions:write', 'Alters recorded financial state; must be attributable to a named integration.', true),
    ('payments:write', 'Moves money. Restricted so a payment-writing credential is a deliberate, reviewable grant.', true),
    ('reconciliation:write', 'Changes reconciliation outcomes, which are financial evidence.', true),
    ('webhooks:write', 'Can redirect PesaGuard event delivery to attacker-chosen endpoints.', true),
    ('developer:write', 'Can create credentials and environments, which can escalate access.', true)
on conflict (scope_name) do nothing;
